const formatters = new Map<string, Intl.DateTimeFormat>()

/** `en` is formatted as `en-IE`: the day before the month, as in Portugal and the design canvas. */
function formatter(language: string, options: Intl.DateTimeFormatOptions) {
  const locale = language === 'en' ? 'en-IE' : language
  const key = `${locale} ${JSON.stringify(options)}`
  let cached = formatters.get(key)
  if (cached === undefined) {
    cached = new Intl.DateTimeFormat(locale, options)
    formatters.set(key, cached)
  }
  return cached
}

/**
 * The day of `instant`, written out for `language`: "3 June 2026", "3 de junho de 2026".
 *
 * English puts the day first, as people in Portugal and Ireland write it and as the design canvas
 * does, rather than the US order ("June 3, 2026") that plain `en` gives.
 */
export function formatDate(instant: string | Date, language: string) {
  return formatter(language, {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date(instant))
}

/** When something was recorded, in this device's time zone: "22 October 2026 at 21:20". */
export function formatDateTime(instant: string, language: string) {
  return formatter(language, { dateStyle: 'long', timeStyle: 'short' }).format(
    new Date(instant),
  )
}

/** The same, shorter, for a line of the trail: "22 Oct, 21:20". */
export function formatShortDateTime(instant: string, language: string) {
  return formatter(language, {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(instant))
}

/**
 * A calendar day as the API sends it, `YYYY-MM-DD`: a day, with no time and no time zone. It is
 * read and written as UTC midnight, so no time zone can move it to the day before.
 */
export type Day = string

/** A calendar month, `YYYY-MM`. */
export type Month = string

const DAY = /^(\d{4})-(\d{2})-(\d{2})$/
const MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/

function pad(value: number, width = 2) {
  return String(value).padStart(width, '0')
}

/** Today on this device's calendar: the day the person sees where they are. */
export function today(now = new Date()): Day {
  return `${pad(now.getFullYear(), 4)}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

export function isDay(text: string): text is Day {
  const match = DAY.exec(text)
  if (match === null) {
    return false
  }
  const [, year, month, day] = match.map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  return date.getUTCMonth() === month - 1 && date.getUTCDate() === day
}

export function isMonth(text: string): text is Month {
  return MONTH.test(text)
}

export function monthOf(day: Day): Month {
  return day.slice(0, 7)
}

/** `month` moved by `months`: `addMonths('2026-01', -1)` is `2025-12`. */
export function addMonths(month: Month, months: number): Month {
  const [year, number] = month.split('-').map(Number)
  const index = year * 12 + number - 1 + months
  return `${pad(Math.floor(index / 12), 4)}-${pad((index % 12) + 1)}`
}

/** `day` moved by `days`: `addDays('2026-03-01', -1)` is `2026-02-28`. */
export function addDays(day: Day, days: number): Day {
  const date = utcDate(day)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

function utcDate(day: Day) {
  const [year, month, date] = day.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, date))
}

/** `day` written out with `options` for `language`, without a time zone moving it. */
export function formatDay(
  day: Day,
  language: string,
  options: Omit<Intl.DateTimeFormatOptions, 'timeZone'>,
) {
  return formatter(language, { ...options, timeZone: 'UTC' }).format(
    utcDate(day),
  )
}

/** "October 2026", "outubro de 2026". */
export function formatMonth(month: Month, language: string) {
  return formatDay(`${month}-01`, language, { month: 'long', year: 'numeric' })
}

/** "October", "outubro": the month alone, where its year is shown nearby. */
export function formatMonthName(month: Month, language: string) {
  return formatDay(`${month}-01`, language, { month: 'long' })
}

/** "Friday", "Sexta-feira": as the first word of a heading, so with a capital in any language. */
export function formatWeekday(day: Day, language: string) {
  const weekday = formatDay(day, language, { weekday: 'long' })
  return weekday.charAt(0).toLocaleUpperCase(language) + weekday.slice(1)
}
