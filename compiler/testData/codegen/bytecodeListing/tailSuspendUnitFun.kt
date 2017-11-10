suspend fun empty() {}
suspend fun withoutReturn() { empty() }
suspend fun withReturn() { return empty() }
suspend fun notTailCall() { empty(); empty() }
suspend fun lambdaAsParameter(c: suspend ()->Unit) { c() }
suspend fun lambdaAsParameterNotTailCall(c: suspend ()->Unit) { c(); c() }
suspend fun lambdaAsParameterReturn(c: suspend ()->Unit) { return c() }
suspend fun returnsInt() = 42
// This should not be tail-call, since the caller should push Unit.INSTANCE on stack
suspend fun callsIntNotTailCall() { returnsInt() }
suspend fun multipleExitPoints(b: Boolean) { if (b) empty() else withoutReturn() }
suspend fun multipleExitPointsNotTailCall(b: Boolean) { if (b) empty() else returnsInt() }

fun ordinary() = 1
inline fun ordinaryInline() { ordinary() }
suspend fun multipleExitPointsWithOrdinaryInline(b: Boolean) { if (b) empty() else ordinaryInline() }