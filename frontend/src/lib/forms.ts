import type { TFunction } from 'i18next'
import { z } from 'zod'
import { ApiError } from '@/api/problem'
import { i18n } from '@/i18n/i18n'
import { currencyDigits, type MoneyInputError } from '@/lib/money'

/** `AccountName` and `CategoryName` in the spec allow 1 to 50 characters. */
export const NAME_MAX_LENGTH = 50

// Messages are functions, so Zod asks for them when it finds the problem and they come out in
// the language the person is using at that moment.

/**
 * The name of an account or a category, with the API's rules: spaces around it are removed (the
 * API removes them too), and something must be left.
 */
export const nameSchema = z
  .string()
  .trim()
  .min(1, { error: () => i18n.t('forms.nameRequired') })
  .max(NAME_MAX_LENGTH, {
    error: () => i18n.t('forms.nameTooLong', { max: NAME_MAX_LENGTH }),
  })

/** Why a typed amount is not one, for everything but an empty field. */
export function moneyInputMessage(
  error: Exclude<MoneyInputError, 'empty'>,
  currency: string,
) {
  switch (error) {
    case 'invalid':
      return i18n.t('forms.amountInvalid')
    case 'too-many-decimals':
      return i18n.t('forms.amountTooManyDecimals', {
        digits: currencyDigits(currency),
      })
    case 'too-large':
      return i18n.t('forms.amountTooLarge')
  }
}

/**
 * What to tell the person when a request failed, chosen by the problem's stable `code`: the API's
 * `detail` is English text for developers.
 */
export function apiErrorMessage(error: unknown, t: TFunction): string {
  if (!(error instanceof ApiError)) {
    // fetch rejects, with a TypeError, only when no answer came back at all.
    return t('errors.network')
  }
  switch (error.code) {
    case 'unauthenticated':
      return t('errors.unauthenticated')
    case 'bad_request':
      return t('errors.badRequest')
    case 'idempotency_key_in_use':
      return t('errors.stillSaving')
    case 'idempotency_key_reused':
      // Only an attempt that succeeded keeps its key: the answer was lost on the way back.
      return t('errors.alreadySaved')
    case 'account_not_found':
    case 'category_not_found':
    case 'transaction_not_found':
      return t('errors.notFound')
    case 'account_archived':
      return t('errors.accountArchived')
    case 'category_archived':
      return t('errors.categoryArchived')
    case 'transaction_already_reversed':
      return t('errors.alreadyReversed')
    default:
      return t('errors.unexpected', { status: error.status })
  }
}
