# WorkManager creates workers by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
