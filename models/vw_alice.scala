import premise.vw._
import java.{util => ju, io => ji, lang => jl}


new VWConfig(
    name        = "vw_alice",
    working_dir = "models",
    passes      = 10,

    basic = new VWBasicConfig(
      loss = "squared",
      adaptive = true,
      bits = 23: jl.Integer,
      mem  = 5: jl.Integer  // lbfgs rank
    )
  )
