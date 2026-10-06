# kotlinx.serialization: os serializadores gerados são achados por reflexão
-keepclassmembers class io.orbe.watch.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
