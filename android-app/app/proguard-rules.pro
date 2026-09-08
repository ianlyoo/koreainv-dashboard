# Persisted broker JSON predates field annotations. Preserve its on-disk contract
# across obfuscated app updates; the market DTOs use explicit JSON parsing.
-keep class com.koreainv.dashboard.network.AccountProfileEnvelope { *; }
-keep class com.koreainv.dashboard.network.TokenCacheEnvelope { *; }
-keep class com.koreainv.dashboard.network.AccountCredential { *; }
-keep class com.koreainv.dashboard.network.AppCredentials { *; }
-keep class com.koreainv.dashboard.network.AuthToken { *; }
-keepattributes Signature,*Annotation*
