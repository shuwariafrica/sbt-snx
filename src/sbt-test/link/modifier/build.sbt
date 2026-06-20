// `Modifier.platform` types a bare partial function (right-hand side transforming a `Native`, `_.library`) at a
// `SNX.modifiers += ...` site, where the element type is inferred bottom-up so a generic constructor would not type.
// The matched modifier injects an unresolvable library, so the link fails on it - proving the modifier both compiled
// at `+=` and applied to the resolved runtime's link.
enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

SNX.modifiers += Modifier.platform {
  case Linux(_, _)   => _.library("snx_modifier_absent")
  case Darwin(_)     => _.library("snx_modifier_absent")
  case Windows(_, _) => _.library("snx_modifier_absent")
}
