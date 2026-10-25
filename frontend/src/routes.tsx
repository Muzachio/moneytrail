import type { RouteObject } from 'react-router'
import { AppLayout } from '@/components/AppLayout'
import { AccountsPage } from '@/pages/AccountsPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { SummaryPage } from '@/pages/SummaryPage'
import { TransactionsPage } from '@/pages/TransactionsPage'

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <SummaryPage /> },
      { path: 'accounts', element: <AccountsPage /> },
      { path: 'transactions', element: <TransactionsPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
