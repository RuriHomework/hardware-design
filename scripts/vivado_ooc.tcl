set repo_root [file normalize [file join [file dirname [info script]] ".."]]
set out_dir [file join $repo_root "build" "vivado"]
file mkdir $out_dir

set part "xc7z010clg400-1"
set top "IssueQueue"
if {[info exists ::env(VIVADO_TOP)]} {
  set top $::env(VIVADO_TOP)
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

set sv_file [file join $repo_root "build" "${top}.sv"]
set_param general.maxThreads $threads

read_verilog -sv $sv_file

synth_design -top $top -part $part -mode out_of_context -flatten_hierarchy rebuilt
if {[llength [get_ports -quiet clock]] > 0} {
  create_clock -period 10.000 [get_ports clock]
}
write_checkpoint -force [file join $out_dir "${top}_ooc_synth.dcp"]
report_utilization -file [file join $out_dir "${top}_ooc_synth_util.rpt"]
report_timing_summary -max_paths 20 -file [file join $out_dir "${top}_ooc_synth_timing.rpt"]
report_timing -max_paths 20 -sort_by group -file [file join $out_dir "${top}_ooc_synth_paths.rpt"]
finish_if_stage "synth" "OOC synth_design" $out_dir $top

opt_design
place_design
phys_opt_design
write_checkpoint -force [file join $out_dir "${top}_ooc_placed.dcp"]
report_utilization -file [file join $out_dir "${top}_ooc_placed_util.rpt"]
report_timing_summary -max_paths 20 -file [file join $out_dir "${top}_ooc_placed_timing.rpt"]
report_timing -max_paths 20 -sort_by group -file [file join $out_dir "${top}_ooc_placed_paths.rpt"]
finish_if_stage "place" "OOC place_design/phys_opt_design" $out_dir $top

route_design
write_checkpoint -force [file join $out_dir "${top}_ooc_routed.dcp"]
report_utilization -file [file join $out_dir "${top}_ooc_routed_util.rpt"]
report_timing_summary -max_paths 20 -file [file join $out_dir "${top}_ooc_routed_timing.rpt"]
report_timing -max_paths 20 -sort_by group -file [file join $out_dir "${top}_ooc_routed_paths.rpt"]
finish_if_stage "route" "OOC route_design" $out_dir $top

puts "Unknown VIVADO_STAGE=$stage. Expected synth, place, or route."
exit 2
