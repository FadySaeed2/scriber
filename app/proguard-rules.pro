# Native code reads these classes' fields by name; renaming them breaks the speech models.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class com.fadysaeed.scriber.** {
    *** Companion;
}
-keepclasseswithmembers class com.fadysaeed.scriber.** {
    kotlinx.serialization.KSerializer serializer(...);
}
