# The helper runs from the APK through app_process; keep its entry points and JNI bridge intact.
-keep class games.forgemaster.keymapper.helper.HelperMain { public static void main(java.lang.String[]); }
-keep class games.forgemaster.keymapper.helper.Cli { public static void main(java.lang.String[]); }
-keep class games.forgemaster.keymapper.helper.NativeInput { native <methods>; }
