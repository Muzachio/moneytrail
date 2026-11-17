import type { RouteObject } from 'react-router'
import { AppLayout } from '@/components/AppLayout'
import { AccountsPage } from '@/pages/AccountsPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { PrivacyPage } from '@/pages/PrivacyPage'
import { SummaryPage } from '@/pages/SummaryPage'
import { TransactionDetailSheet } from '@/pages/TransactionDetailSheet'
import { TransactionsPage } from '@/pages/TransactionsPage'

export const routes: RouteObject[] = [
  // Outside AppLayout, so it opens without a session: the sign-in page links to it.
  { path: '/privacy', element: <PrivacyPage /> },
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <SummaryPage /> },
      { path: 'accounts', element: <AccountsPage /> },
      {
        path: 'transactions',
        element: <TransactionsPage />,
        children: [{ path: ':id', element: <TransactionDetailSheet /> }],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
