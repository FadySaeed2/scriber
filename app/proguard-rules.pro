# Native code reads these classes' fields by name; renaming them breaks speech and language models.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class com.fadysaeed.scriber.** {
    *** Companion;
}
-keepclasseswithmembers class com.fadysaeed.scriber.** {
    kotlinx.serialization.KSerializer serializer(...);
}
