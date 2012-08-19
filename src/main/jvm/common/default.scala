package premise.common

object default {

  //
  // Implicit conversions
  //

  class AnyOps[X](x: X) {

    def withEffect(f: X => Unit): X = { f(x); x }

    def into[Y](f: X => Y): Y = f(x)

    def mapNull                (x0: => X)  : X = if (x == null) x0   else x
    def mapNonNull [Y >: Null] (f: X => Y) : Y = if (x != null) f(x) else null

  }
  implicit def AnyOps[X](x: X) = new AnyOps(x)

  class AnyByNameOps[X](x: => X) {
  }
  implicit def AnyByNameOps[X](x: => X) = new AnyByNameOps(x)

}
