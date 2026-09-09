import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'OpenCall AI',
  description: 'Open-source, Internet-first calling with local AI voice agents',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
