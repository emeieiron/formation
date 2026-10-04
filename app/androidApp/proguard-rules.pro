# Ktor's IntelliJ debugger detector reads JVM management beans that Android doesn't have.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# ML Kit (the QR scanner) finds its component registrars through manifest metadata and builds them
# by reflection, so their no-argument constructors must survive.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
