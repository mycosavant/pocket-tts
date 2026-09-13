//! `pocket-speak`: read text on stdin aloud in a Pocket TTS voice.

use std::io::Read;
use std::path::PathBuf;
use std::process::ExitCode;

use pocket_tts_engine::text::{self, Chunking};
use pocket_tts_engine::{Cancel, EndReason, Engine};

const USAGE: &str = "\
usage: pocket-speak [options] < text

  --out PATH          write a 16-bit 24 kHz WAV
  --model-dir DIR     model directory (default: $POCKET_TTS_MODEL_DIR, else the cache)
  --voice NAME|PATH   voice embedding (default: alba)
  --chunking MODE     sentence | packed | packed-raw (default: packed)
  --plain             do not strip Markdown
  --seed N            reproducible sampling
  --threads N         ONNX Runtime intra-op threads (default: 2)
  --stats             one JSON line per chunk on stderr
";

struct Options {
    out: Option<PathBuf>,
    model_dir: PathBuf,
    voice: String,
    chunking: Chunking,
    plain: bool,
    seed: Option<u64>,
    threads: usize,
    stats: bool,
}

fn default_model_dir() -> PathBuf {
    if let Some(dir) = std::env::var_os("POCKET_TTS_MODEL_DIR") {
        return PathBuf::from(dir);
    }
    let base = if cfg!(windows) {
        std::env::var_os("LOCALAPPDATA").map(PathBuf::from)
    } else {
        std::env::var_os("XDG_CACHE_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".cache")))
    };
    base.unwrap_or_else(|| PathBuf::from("."))
        .join("pocket-tts-rs")
        .join("english_2026-04")
}

fn parse(args: impl Iterator<Item = String>) -> Result<Options, String> {
    let mut options = Options {
        out: None,
        model_dir: default_model_dir(),
        voice: "alba".to_string(),
        chunking: Chunking::PACKED_DEFAULT,
        plain: false,
        seed: None,
        threads: 2,
        stats: false,
    };
    let mut args = args.peekable();
    while let Some(arg) = args.next() {
        let mut value = |name: &str| args.next().ok_or_else(|| format!("{name} needs a value"));
        match arg.as_str() {
            "--out" => options.out = Some(PathBuf::from(value("--out")?)),
            "--model-dir" => options.model_dir = PathBuf::from(value("--model-dir")?),
            "--voice" => options.voice = value("--voice")?,
            "--chunking" => {
                options.chunking = match value("--chunking")?.as_str() {
                    "sentence" => Chunking::Sentence,
                    "packed" => Chunking::PACKED_DEFAULT,
                    "packed-raw" => Chunking::Packed {
                        target: 200,
                        max: 400,
                        join_tail: false,
                    },
                    other => return Err(format!("unknown chunking {other:?}")),
                }
            }
            "--plain" => options.plain = true,
            "--seed" => {
                options.seed = Some(
                    value("--seed")?
                        .parse()
                        .map_err(|e| format!("--seed: {e}"))?,
                )
            }
            "--threads" => {
                options.threads = value("--threads")?
                    .parse()
                    .map_err(|e| format!("--threads: {e}"))?
            }
            "--stats" => options.stats = true,
            "-h" | "--help" => return Err(String::new()),
            other => return Err(format!("unknown argument {other:?}")),
        }
    }
    Ok(options)
}

fn main() -> ExitCode {
    let options = match parse(std::env::args().skip(1)) {
        Ok(options) => options,
        Err(message) => {
            if !message.is_empty() {
                eprintln!("pocket-speak: {message}");
            }
            eprint!("{USAGE}");
            return ExitCode::from(2);
        }
    };
    match run(options) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("pocket-speak: {message}");
            ExitCode::FAILURE
        }
    }
}

fn run(options: Options) -> Result<(), String> {
    let Some(out) = options.out.clone() else {
        return Err("--out is required until playback lands".to_string());
    };
    let mut input = String::new();
    std::io::stdin()
        .read_to_string(&mut input)
        .map_err(|e| format!("reading stdin: {e}"))?;
    let speakable = if options.plain {
        input
    } else {
        text::speakable(&input)
    };
    let chunks = text::chunk(&speakable, options.chunking);
    if chunks.is_empty() {
        return Err("nothing to say".to_string());
    }

    let mut engine =
        Engine::load(&options.model_dir, options.threads).map_err(|e| e.to_string())?;
    if let Some(seed) = options.seed {
        engine.set_seed(seed);
    }
    let voice_path =
        if options.voice.contains(['/', '\\']) || options.voice.ends_with(".safetensors") {
            PathBuf::from(&options.voice)
        } else {
            options
                .model_dir
                .join("embeddings")
                .join(format!("{}.safetensors", options.voice))
        };
    let voice = engine.load_voice(&voice_path).map_err(|e| e.to_string())?;
    let sample_rate = engine.sample_rate();

    let spec = hound::WavSpec {
        channels: 1,
        sample_rate,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };
    let mut writer =
        hound::WavWriter::create(&out, spec).map_err(|e| format!("{}: {e}", out.display()))?;
    let cancel = Cancel::new();
    for (index, chunk) in chunks.iter().enumerate() {
        let mut write_error = None;
        let stats = engine
            .synthesize(&chunk.speech, &voice, &cancel, |pcm| {
                for sample in pcm {
                    let quantized = (sample.clamp(-1.0, 1.0) * i16::MAX as f32).round() as i16;
                    if let Err(e) = writer.write_sample(quantized) {
                        write_error = Some(e);
                        return false;
                    }
                }
                true
            })
            .map_err(|e| e.to_string())?;
        if let Some(e) = write_error {
            return Err(format!("{}: {e}", out.display()));
        }
        let silence = (chunk.pause_after_seconds * sample_rate as f32) as usize;
        for _ in 0..silence {
            writer
                .write_sample(0_i16)
                .map_err(|e| format!("{}: {e}", out.display()))?;
        }
        if options.stats {
            let audio_seconds = stats.samples as f32 / sample_rate as f32;
            let speed = audio_seconds / (stats.generation_ms.max(1) as f32 / 1000.0);
            eprintln!(
                "{{\"chunk\":{index},\"chars\":{},\"tokens\":{},\"frames\":{},\"max_frames\":{},\"eos_frame\":{},\"end\":\"{}\",\"audio_s\":{audio_seconds:.2},\"gen_ms\":{},\"first_audio_ms\":{},\"x_realtime\":{speed:.2}}}",
                chunk.speech.chars().count(),
                stats.tokens,
                stats.frames,
                stats.max_frames,
                stats
                    .eos_frame
                    .map_or("null".to_string(), |f| f.to_string()),
                match stats.end {
                    EndReason::EndOfSpeech => "eos",
                    EndReason::Budget => "budget",
                    EndReason::Cancelled => "cancelled",
                },
                stats.generation_ms,
                stats
                    .first_audio_ms
                    .map_or("null".to_string(), |f| f.to_string()),
            );
        }
    }
    writer
        .finalize()
        .map_err(|e| format!("{}: {e}", out.display()))?;
    Ok(())
}
