const formatters = new Map<string, Intl.DateTimeFormat>()

/**
 * The day of `instant`, written out for `language`: "3 June 2026", "3 de junho de 2026".
 *
 * English puts the day first, as people in Portugal and Ireland write it and as the design canvas
 * does, rather than the US order ("June 3, 2026") that plain `en` gives.
 */
export function formatDate(instant: string | Date, language: string) {
  const locale = language === 'en' ? 'en-IE' : language
  let formatter = formatters.get(locale)
  if (formatter === undefined) {
    formatter = new Intl.DateTimeFormat(locale, {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    })
    formatters.set(locale, formatter)
  }
  return formatter.format(new Date(instant))
}
