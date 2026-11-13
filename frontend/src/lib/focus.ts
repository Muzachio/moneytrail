/**
 * For a dialog's `onCloseAutoFocus`: puts the focus back on the button that opened it.
 *
 * Radix does that by itself only for a `DialogTrigger`. These dialogs open from a row's menu, whose
 * item is gone once the menu closes, or from more than one button, so without this the focus
 * would land on the page's body and a keyboard user would start again from the top.
 */
export function returnFocus(
  event: Event,
  opener: HTMLElement | null | undefined,
) {
  if (opener?.isConnected) {
    event.preventDefault()
    opener.focus()
  }
}
