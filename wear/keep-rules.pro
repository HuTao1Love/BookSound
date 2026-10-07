# R8 moved single classes of the optimized libraries out of their package while their
# package-private superclass stayed behind (kotlin.text.StringsKt__StringBuilderKt moved to the
# root package, its superclass StringsKt__IndentKt did not), so the app crashed on start with
# IllegalAccessError. Keep every class in its package; classes are still renamed and optimized.
-keeppackagenames androidx.**,kotlin.**,kotlinx.**
