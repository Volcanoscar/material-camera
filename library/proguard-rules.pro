-keep class !android.support.v7.internal.view.menu.**,** {*;}
-keep class butterknife.** { *; }
-dontwarn butterknife.internal.**
-keep class **$$ViewInjector { *; }
-keepclasseswithmembernames class * {
    @butterknife.* <fields>;
}
-keepclasseswithmembernames class * {
    @butterknife.* <methods>;
}
-dontwarn
-ignorewarnings

-keep class com.skillfitness.extempore.** { *; }
-keep class com.skillfitness.skillfitness.** { *; }
-keep class com.skillfitness.skillfitness.api.objects.Exercise { *; }