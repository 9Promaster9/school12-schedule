-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ru.school12.schedule.model.**$$serializer { *; }
-keepclassmembers class ru.school12.schedule.model.** {
    *** Companion;
}
-keepclasseswithmembers class ru.school12.schedule.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
