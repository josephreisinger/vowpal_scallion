package premise.vw

import premise.vw.common.default._

import scala.collection.mutable

import scala.sys.process.{Process,ProcessIO}
import scala.io.Source
import org.eintr.loglady.Logging 
import java.{util => ju, io => ji, lang => jl}
import scala.collection.JavaConverters._

// The reason all the parameters get broken up like this is because case classes are subject to the tuple limitation (max 23 fields)
case class VWBasicConfig(
  // VW options
  val bits: jl.Integer         = null: jl.Integer,
  val loss: String             = null: String,
  val l1: jl.Double            = null: jl.Double,
  val l2: jl.Double            = null: jl.Double,
  val learning_rate: jl.Double = null: jl.Double,
  val quadratic: String        = null: String,
  val audit: jl.Boolean        = null: jl.Boolean,
  val power_t: jl.Double       = null: jl.Double,
  val initial_t: jl.Double     = null: jl.Double,
  val decay_learning_rate: jl.Double  = null: jl.Double,
  val oaa: jl.Integer                 = null: jl.Integer,
  val mem: jl.Integer                 = null: jl.Integer,
  val adaptive: jl.Boolean            = null: jl.Boolean
)

case class VWLDAConfig(
  val lda: jl.Integer       = null: jl.Integer,
  val lda_D: jl.Integer     = null: jl.Integer,
  val lda_rho: jl.Double    = null: jl.Double,
  val lda_alpha: jl.Double  = null: jl.Double,
  val minibatch: jl.Integer = null: jl.Integer
) 

case class VWConfig(
  val name: String,
  val vw: String = "vw",
  val working_dir: String = ".",

  val passes: Int              = 1,  // used differently train / test so it has to have a default value

  val basic:    VWBasicConfig    = new VWBasicConfig,
  val lda:      VWLDAConfig      = new VWLDAConfig,

  val total: jl.Integer     = null: jl.Integer,
  val node: jl.Integer      = null: jl.Integer,
  val unique_id: jl.Integer = null: jl.Integer,
  val span_server: String   = null: String,

  val bfgs: jl.Boolean                = null: jl.Boolean,

  val incremental: jl.Boolean         = false
) extends Logging {
  lazy val handle = 
    if (node == null) name
    else              "%s.%d" format (name, node)

  lazy val model_filename = "%s.model" format handle
  lazy val cache_filename = "%s.cache" format handle

  lazy val full_working_dir = new ji.File(working_dir, name) withEffect(_.mkdirs)

  lazy val model_file_path = new ji.File(full_working_dir, model_filename).toString
  lazy val cache_file_path = new ji.File(full_working_dir, cache_filename).toString

  def validate {
    // Do some sanity checking for compatability between models
    if (node != null) {
      assert(total != null)
      assert(unique_id != null)
      assert(span_server != null)
    }
    if (lda.lda != null) {
      assert(basic.l1 == null)
      assert(basic.l2 == null)
      assert(basic.loss == null)
      assert(basic.adaptive == null)
      assert(basic.oaa == null)
      assert(bfgs == null)
    } else {
      assert(lda.lda_D == null)
      assert(lda.lda_rho == null)
      assert(lda.lda_alpha == null)
      assert(lda.minibatch == null)
    }
  }

  def vw_base_command = "%s %s" format (vw, vw_arg_string)
  def vw_arg_string = vw_args mkString " "
  def vw_args = Seq(
    Option(basic.bits)                map ("-b %d"                    format _),
    Option(basic.learning_rate)       map ("--learning_rate=%f"       format _),
    Option(basic.l1)                  map ("--l1=%f"                  format _),
    Option(basic.l2)                  map ("--l2=%f"                  format _),
    Option(basic.initial_t)           map ("--initial_t=%f"           format _),
    Option(basic.quadratic)           map ("-q %s"                    format _),
    Option(basic.power_t)             map ("--power_t=%f"             format _),
    Option(basic.loss)                map ("--loss_function=%s"       format _),
    Option(basic.decay_learning_rate) map ("--decay_learning_rate=%f" format _),
    Option(lda.lda)                 map ("--lda=%d"                 format _),
    Option(lda.lda_D)               map ("--lda_D=%d"               format _),
    Option(lda.lda_rho)             map ("--lda_rho=%f"             format _),
    Option(lda.lda_alpha)           map ("--lda_alpha=%f"           format _),
    Option(lda.minibatch)           map ("--minibatch=%d"           format _),
    Option(basic.oaa)               map ("--oaa=%d"                 format _),
    Option(unique_id)      map ("--unique_id=%d"           format _),
    Option(total)          map ("--total=%d"               format _),
    Option(node)           map ("--node=%d"                format _),
    Option(span_server)    map ("--span_server=%s"         format _),
    Option(basic.mem)                     map ("--mem=%d"                 format _),
    if (Option(basic.audit)    == Some(true)) Some("--audit")    else None,
    if (Option(basic.adaptive) == Some(true)) Some("--adaptive") else None,
    if (Option(bfgs)     == Some(true)) Some("--bfgs")     else None
  ).flatten

  def vw_train_command: String = vw_train_command(cache_file_path, model_file_path) 
  def vw_train_command(cf: String, mf: String): String =
    if (new ji.File(mf).exists) {
      log.debug("Training incremental...")
      "%s --passes %d --cache_file %s -i %s -f %s" format (vw_base_command, passes, cf, mf, mf)
    } else {
      "%s --passes %d --cache_file %s -f %s" format (vw_base_command, passes, cf, mf)
    }

  def vw_test_command: String = vw_test_command(model_file_path)
  def vw_test_command(mf: String): String = "%s -p stdout -t -i %s" format (vw_base_command, mf)
}

trait VWConfigured {
    val config: VWConfig
}

// Generic base class that supports model training (But not prediction)
trait VWTrainable extends VWConfigured with Logging {
    // Create a new model and train it using the specified stream
    def train(input: Iterator[Iterable[String]]) {
        prepare_training

        // Calling exitValue Blocks until complete
        log.debug("running training [%s]" format config.vw_train_command)
        Process(config.vw_train_command).run(
          // these will all be separate threads (nice!)
          // NOTE: remember to flush!
          // new ProcessIO(stdin => try     { input foreach { ln => stdin.write(ln getBytes "UTF-8"); stdin.write("\n" getBytes "UTF-8") }; stdin.flush }  
          new ProcessIO(
            _stdin => {
                val stdin = new ji.PrintWriter(_stdin)
                try     { input foreach { ln => ln foreach { lnp => stdin.write(lnp); stdin.write(" ") }; stdin.write("\n") } }
                catch   { case e => log.error("Training error [%s]".format(e)) } 
                finally { stdin.close; log.debug("finished streaming instances.") }
            },
            stdout => Source.fromInputStream(stdout).getLines.foreach(line => ()),
            stderr => Source.fromInputStream(stderr).getLines.foreach(line => log.debug(line))
           )
        ).exitValue
    }

    def prepare_training {
      // Remove the old cache and model files
      if (!config.incremental) {
        val cache_file = new ji.File(config.cache_file_path)
        val model_file = new ji.File(config.model_file_path)

        if (cache_file.exists) cache_file.delete
        if (model_file.exists) model_file.delete
      }
    }
}

// Break out the typed components from the hierarchy
trait VW[@specialized(Int,Double) T] extends VWTrainable with Logging {

    def parse_output_line(raw_line: String): T

    // Run prediction over an iterator and batch up the results
    def batch_predict(input: Iterator[Iterable[String]]): Iterable[T] = {
        // Calling exitValue Blocks until complete
        log.debug("running predict [%s]" format config.vw_test_command)
        val outs = new mutable.ArrayBuffer[T]
        val proc = Process(config.vw_test_command).run(
          new ProcessIO(
                        _stdin => {
                            val stdin = new ji.PrintWriter(_stdin)
                            try     { input foreach { ln => ln foreach { lnp => stdin.write(lnp); stdin.write(" ") }; stdin.write("\n") } }
                            catch   { case e => log.error("Training error [%s]".format(e)) } 
                            finally { stdin.close; log.debug("finished streaming instances.") }
                        },
                        stdout => try { Source.fromInputStream(stdout).getLines foreach (x => outs.append(parse_output_line(x))) }
                                  finally { stdout.close },
                        stderr => try { Source.fromInputStream(stderr).getLines foreach (log.debug(_)); }
                                  finally { stderr.close }
                       )
        )
        proc.exitValue
        outs
    }
}

// XXX: so we're using VWTrainable here to be polymorphic and avoid serious type-injection all over the place. but this is gross. HALP!
trait VWFactory {
  def make(vw_config: VWConfig): VWTrainable
}

trait VWBinaryClassifierFactory extends VWFactory {
  def make(vw_config: VWConfig): VWTrainable = new VW[Double] {
    val config = vw_config
    def parse_output_line(raw_line: String): Double = raw_line.toDouble
  }
}

trait VWMulticlassClassifierFactory extends VWFactory {
  val label_map: Map[String,String]
  def make(vw_config: VWConfig): VWTrainable = new VW[String] {
    val config = vw_config
    def parse_output_line(raw_line: String): String = label_map(raw_line.toDouble.toInt)
  }
}

trait VWLDAFactory extends VWFactory {
  def make(vw_config: VWConfig): VWTrainable = new VW[Iterable[Double]] {
    val config = vw_config
    val topics = config.lda.lda
    def parse_output_line(raw_line: String): Iterable[Double] = {
        val tokens = raw_line.split(" ")
        // log.info("parse_pred [%s]".format(raw_line))
        // XXX: there is a weird extra space in there
        assert(tokens.length == topics + 2, { log.error("Incorrect prediction token sequence: [%s]".format(tokens mkString " ")) })
        (tokens take topics).map(_.toDouble)
    }
  }
}
