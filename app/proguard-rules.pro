# MiruTime keeps everything via the AAR's consumer-rules.pro; nothing extra to keep.
# (TextView-only UI, no reflection of our own.)
#
# FINDING (core consumer-rules.pro gap, fixed here spike-locally — NOT in core):
# release R8 fails without these dontwarns. All three are unused-at-runtime
# references from core's transitive deps:
# - org.apache.bcel.** : LuaJ luajc (Lua->JVM-bytecode compiler; core uses the
#   interpreter only, luajc never runs on ART).
# - javax.script.**  : LuaJ JSR-223 engine (absent on Android; unused).
# - java.beans.**    : snakeyaml introspector (java.beans absent on Android).
# Recommendation for core: ship these three -dontwarn lines in
# mirutime-core/consumer-rules.pro. First-failure log: /tmp/spike/r8-first-failure.log
-dontwarn org.apache.bcel.**
-dontwarn javax.script.**
-dontwarn java.beans.**
