import { act, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { MoneyInput } from '@/components/MoneyInput'
import { i18n } from '@/i18n/i18n'
import { parseMoneyInput } from '@/lib/money'

/** A form field as the M1 forms will build it: the input keeps the text, the form parses it. */
function AmountField() {
  const [text, setText] = useState('')
  const parsed = parseMoneyInput(text, 'EUR')
  return (
    <>
      <label htmlFor="amount">Amount</label>
      <MoneyInput
        id="amount"
        currency="EUR"
        value={text}
        onChange={setText}
        aria-invalid={text !== '' && !parsed.ok}
      />
      <output>{parsed.ok ? parsed.amountMinor : parsed.error}</output>
    </>
  )
}

function symbolSide() {
  return screen
    .getByText('€')
    .closest('[data-slot="input-group-addon"]')
    ?.getAttribute('data-align')
}

describe('MoneyInput', () => {
  it('keeps what is typed, for the form to read as minor units', () => {
    render(<AmountField />)
    const input = screen.getByLabelText('Amount')

    fireEvent.change(input, { target: { value: '12,50' } })

    expect(input).toHaveValue('12,50')
    expect(screen.getByRole('status')).toHaveTextContent('1250')
    expect(input).toHaveAttribute('aria-invalid', 'false')

    fireEvent.change(input, { target: { value: '12.505' } })

    expect(input).toHaveValue('12.505')
    expect(screen.getByRole('status')).toHaveTextContent('too-many-decimals')
    expect(input).toHaveAttribute('aria-invalid', 'true')
  })

  it('opens the decimal keypad and tells screen readers the currency', () => {
    render(<AmountField />)
    const input = screen.getByLabelText('Amount')

    expect(input).toHaveAttribute('inputmode', 'decimal')
    expect(input).toHaveAccessibleDescription('€')
  })

  it('puts the currency where the language writes it', async () => {
    render(<AmountField />)

    expect(symbolSide()).toBe('inline-start')
    expect(screen.getByLabelText('Amount')).toHaveAttribute(
      'placeholder',
      '0.00',
    )

    await act(() => i18n.changeLanguage('pt-PT'))

    expect(symbolSide()).toBe('inline-end')
    expect(screen.getByLabelText('Amount')).toHaveAttribute(
      'placeholder',
      '0,00',
    )
  })
})
