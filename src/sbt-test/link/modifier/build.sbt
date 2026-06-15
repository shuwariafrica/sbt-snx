// The `Modifier.platform` carrier: a per-platform native-config transform added with `+=`. This is the load-bearing
// typing proof - the carrier exists so a bare partial function whose right-hand side transforms a `Native` (`_.library`)
// types at a `SNX.modifiers += ...` site (the element type is inferred bottom-up there, so a generic constructor would
// not). The matched modifier injects an unresolvable library, so the link fails on it - proving the modifier both
// compiled at `+=` and was applied to the resolved runtime's link.
enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

SNX.modifiers += Modifier.platform {
  case Linux(_, _)   => _.library("snx_modifier_absent")
  case Darwin(_)     => _.library("snx_modifier_absent")
  case Windows(_, _) => _.library("snx_modifier_absent")
}
