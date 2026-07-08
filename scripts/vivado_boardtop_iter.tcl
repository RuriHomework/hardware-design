set repo_root [file normalize [file join [file dirname [info script]] ".."]]
set out_dir [file join $repo_root "build" "vivado"]
file mkdir $out_dir

set part "xc7z010clg400-1"
set top "BoardTop"
set sv_file [file join $repo_root "build" "${top}.sv"]
set vivado_sv_file [file join $out_dir "${top}_vivado.sv"]
set xdc_file [file join $repo_root "constraints.xdc"]
if {[info exists ::env(VIVADO_XDC)]} {
  set xdc_file [file join $repo_root $::env(VIVADO_XDC)]
}
set stage "place"
if {[info exists ::env(VIVADO_STAGE)]} {
  set stage $::env(VIVADO_STAGE)
}
set threads 6
if {[info exists ::env(VIVADO_THREADS)]} {
  set threads $::env(VIVADO_THREADS)
}

proc finish_if_stage {wanted label out_dir top} {
  global stage
  if {$stage eq $wanted} {
    puts "Stopping after $label because VIVADO_STAGE=$stage"
    exit 0
  }
}

set_param general.maxThreads $threads

set in_fh [open $sv_file r]
set out_fh [open $vivado_sv_file w]
puts $out_fh {`define ENABLE_INITIAL_MEM_}
puts $out_fh {`define ENABLE_INITIAL_REG_}
puts $out_fh [read $in_fh]
close $in_fh
close $out_fh

read_verilog -sv $vivado_sv_file
read_xdc $xdc_file

synth_design -top $top -part $part -flatten_hierarchy rebuilt
write_checkpoint -force [file join $out_dir "${top}_synth.dcp"]
report_utilization -file [file join $out_dir "${top}_synth_util.rpt"]
report_timing_summary -max_paths 20 -file [file join $out_dir "${top}_synth_timing.rpt"]
report_timing -max_paths 20 -sort_by group -file [file join $out_dir "${top}_synth_paths.rpt"]
finish_if_stage "synth" "synth_design" $out_dir $top

opt_design
place_design
phys_opt_design
write_checkpoint -force [file join $out_dir "${top}_placed.dcp"]
report_utilization -file [file join $out_dir "${top}_placed_util.rpt"]
report_timing_summary -max_paths 20 -file [file join $out_dir "${top}_placed_timing.rpt"]
report_timing -max_paths 20 -sort_by group -file [file join $out_dir "${top}_placed_paths.rpt"]
finish_if_stage "place" "place_design/phys_opt_design" $out_dir $top

route_design
write_checkpoint -force [file join $out_dir "${top}_routed.dcp"]
report_utilization -file [file join $out_dir "${top}_routed_util.rpt"]
report_timing_summary -max_paths 20 -file [file join $out_dir "${top}_routed_timing.rpt"]
report_timing -max_paths 20 -sort_by group -file [file join $out_dir "${top}_routed_paths.rpt"]
finish_if_stage "route" "route_design" $out_dir $top

if {$stage ne "bit"} {
  puts "Unknown VIVADO_STAGE=$stage. Expected synth, place, route, or bit."
  exit 2
}

write_bitstream -force [file join $out_dir "${top}.bit"]
