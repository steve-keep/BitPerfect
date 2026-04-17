import type { Metadata } from "next";
import "./globals.css";
import Navigation from "../components/Navigation";

export const metadata: Metadata = {
  title: "BitPerfect - Audiophile CD Ripping for Android",
  description: "Rip a CD on Android and verify that it's bit perfect. A diagnostic, precision-focused experience for audiophiles.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body className="antialiased bg-background text-foreground min-h-screen flex flex-col">
        <Navigation />
        <main className="flex-grow">
          {children}
        </main>
        <footer className="bg-surface py-8 text-center text-muted border-t border-surface-elevated mt-12">
          <p>🎧 Built for audiophiles, with ❤️ and precision.</p>
          <p className="mt-2 text-sm">© {new Date().getFullYear()} BitPerfect</p>
        </footer>
      </body>
    </html>
  );
}
