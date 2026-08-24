# sherpa-onnx reaches into these classes from JNI, so their names and the
# fields/constructors it looks up have to survive shrinking.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }
