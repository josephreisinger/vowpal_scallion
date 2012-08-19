package premise.vw

import scala.io.Source
import org.eintr.loglady.Logging 
import java.{util => ju, io => ji}

import com.twitter.util.Eval
import java.io.File
import org.clapper.argot._
import ArgotConverters._

                

object Train extends Logging {

  def main(args: Array[String]) = { 
    val parser = new ArgotParser("vw_train")
    val model_file_opt = parser.option[String](List("model"), "m", "")
        
    try {
      parser.parse(args)

      val model_file = model_file_opt.value.get

      log.info("model=[%s]" format (model_file))

      val eval = new Eval
      val model_config = eval[VWConfig](new File(model_file))

      val vw = new VW(model_config)

      // Each training line is actually an Iterable of chunks of a full training instance
      vw.train(Source.fromInputStream(System.in)("UTF-8").getLines.map(x=>Seq(x)))
    }

    catch {
      case e: ArgotUsageException => log.error(e.message)
    }

  }
}
