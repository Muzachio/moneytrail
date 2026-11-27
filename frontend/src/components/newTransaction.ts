import { useOutletContext } from 'react-router'

/** What the phone's centre button does instead of a new transaction, on a page that changes it. */
export type CentreAction = {
  /** Its name for screen readers: "Add an expense to Porto weekend". */
  label: string
  /** `opener` gets the focus back when the dialog it opens closes. */
  run: (opener: HTMLElement) => void
}

/** What the app's layout hands to its pages. */
export type AppOutletContext = {
  /**
   * Opens the new-transaction dialog of the header, from another button too, such as the empty
   * list's. `opener` gets the focus back when the dialog closes.
   */
  newTransaction: (opener: HTMLElement) => void
  /**
   * Gives the bottom tabs' centre button to the page, as a group's page does to add an expense to
   * it; `null` gives it back to the new transaction.
   */
  setCentreAction: (action: CentreAction | null) => void
}

export function useNewTransaction() {
  return useOutletContext<AppOutletContext>().newTransaction
}

export function useSetCentreAction() {
  return useOutletContext<AppOutletContext>().setCentreAction
}
