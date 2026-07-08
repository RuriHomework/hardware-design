set repo_root [file normalize [file join [file dirname [info script]] ".."]]
set bit_file [file join $repo_root "build" "vivado" "BoardTop.bit"]
if {[info exists ::env(VIVADO_BITFILE)]} {
  set bit_file [file normalize $::env(VIVADO_BITFILE)]
}

if {![file exists $bit_file]} {
  puts "Missing bitstream: $bit_file"
  exit 1
}

open_hw_manager
connect_hw_server

set targets [get_hw_targets -quiet]
if {[llength $targets] == 0} {
  puts "No hardware targets found"
  exit 1
}

set target [lindex $targets 0]
current_hw_target $target
open_hw_target $target

set devices [get_hw_devices -quiet xc7z010*]
if {[llength $devices] == 0} {
  set devices [get_hw_devices -quiet]
}
if {[llength $devices] == 0} {
  puts "No hardware devices found"
  exit 1
}

set dev [lindex $devices 0]
current_hw_device $dev
refresh_hw_device $dev
set_property PROGRAM.FILE $bit_file $dev
program_hw_devices $dev
refresh_hw_device $dev

puts "Programmed $dev with $bit_file"
