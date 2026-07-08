import chisel3._
import circt.stage.ChiselStage

object Elaborate extends App {
  // 默认生成 Top；通过命令行参数可指定其他模块
  val target = args.headOption match {
    case Some("--top") => args.tail.headOption.getOrElse("Top")
    case _ => "Top"
  }
  val mod: () => RawModule = target match {
    case "Top"       => () => new top.Top
    case "BoardTop"  => () => new top.BoardTop
    case "BlinkTop"  => () => new top.BlinkTop
    case "Core"      => () => new top.Core
    case "Fetch"     => () => new core.frontend.Fetch
    case "Alu"       => () => new core.backend.units.Alu
    case "Bru"       => () => new core.backend.units.Bru
    case "Lsu"       => () => new core.backend.units.Lsu
    case "MulDiv"    => () => new core.backend.units.MulDiv
    case "Decoder"   => () => new isa.Decoder
    case "Backend"   => () => new core.backend.Backend
    case "Rob"       => () => new core.backend.Rob
    case "IssueQueue"=> () => new core.backend.IssueQueue
    case "StoreBuffer" => () => new core.backend.StoreBuffer
    case "BranchPredictor" => () => new core.frontend.BranchPredictor
    case other       => throw new IllegalArgumentException(s"Unknown target: $other")
  }
  ChiselStage.emitSystemVerilogFile(
    mod(),
    args = Array("--target-dir", "build")
  )
}
