# sherpa-onnx reaches into these classes from JNI, so their names and the
# fields/constructors it looks up have to survive shrinking.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }

# The streaming audio callback, resolved from native code by name and exact
# signature: GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;").
#
# PocketTts.audioCallback already avoids a lambda so the specialised method is
# emitted rather than only the erased `invoke(Object)Object` bridge. That gets
# it past D8. R8 then took it away a second time, by a different route: it
# inlined the specialised method into its own bridge, leaving one method whose
# descriptor is (Ljava/lang/Object;)Ljava/lang/Object;. GetMethodID found
# nothing, and because sherpa-onnx does not clear the pending exception it
# returned "keep generating" - so a release build synthesised whole utterances
# into nowhere and reported a failure after a long silence, where the debug
# build aborted loudly under CheckJNI.
#
# The rule is written against the interface rather than the class so it keeps
# working if the callback moves. It matches almost nothing else: Function1
# implementations specialised to (float[]) -> Integer are not common.
-keepclassmembers class * implements kotlin.jvm.functions.Function1 {
    java.lang.Integer invoke(float[]);
}
