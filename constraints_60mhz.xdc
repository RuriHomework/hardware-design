# Minimal EBAZ4205 constraints for BoardTop at 60 MHz.
# Pin references: xjtuecho/EBAZ4205 Development/EBAZ4205.xdc.

set_property PACKAGE_PIN N18 [get_ports clock]
set_property IOSTANDARD LVCMOS33 [get_ports clock]
create_clock -period 16.667 [get_ports clock]

set_property PACKAGE_PIN W13 [get_ports greenLED]
set_property IOSTANDARD LVCMOS33 [get_ports greenLED]
set_property DRIVE 12 [get_ports greenLED]

set_property PACKAGE_PIN W14 [get_ports redLED]
set_property IOSTANDARD LVCMOS33 [get_ports redLED]
set_property DRIVE 12 [get_ports redLED]
