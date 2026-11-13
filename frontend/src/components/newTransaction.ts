import { useOutletContext } from 'react-router'

/** What the app's layout hands to its pages. */
export type AppOutletContext = {
  /**
   * Opens the new-transaction dialog of the header, from another button too, such as the empty
   * list's. `opener` gets the focus back when the dialog closes.
   */
  newTransaction: (opener: HTMLElement) => void
}

export function useNewTransaction() {
  return useOutletContext<AppOutletContext>().newTransaction
}
