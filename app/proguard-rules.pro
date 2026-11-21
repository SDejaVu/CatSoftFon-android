# Add project-specific ProGuard rules here. Release build currently has minifyEnabled=false,
# so this file isn't exercised yet, but is wired in for when/if you turn minification on.

# Linphone SDK uses JNI heavily; keep its classes untouched if minification is ever enabled.
-keep class org.linphone.** { *; }
-keep class org.linphone.core.** { *; }
