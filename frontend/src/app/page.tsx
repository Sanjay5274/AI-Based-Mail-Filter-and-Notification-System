import React from "react";
import { Mail, Sparkles, ShieldCheck } from "lucide-react";

export default function HomePage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-8 bg-zinc-50 dark:bg-zinc-950">
      <div className="max-w-xl w-full text-center space-y-6">
        <div className="inline-flex items-center justify-center p-3 rounded-2xl bg-blue-100 dark:bg-blue-950 text-blue-600 dark:text-blue-400">
          <Mail className="w-10 h-10" />
        </div>
        <h1 className="text-4xl font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
          InboxIQ
        </h1>
        <p className="text-lg text-zinc-600 dark:text-zinc-400">
          AI-Powered Context-Aware Email Intelligence &amp; Notification Platform.
        </p>

        <div className="grid grid-cols-2 gap-4 pt-4 text-left">
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900">
            <div className="flex items-center space-x-2 text-zinc-800 dark:text-zinc-200 font-semibold mb-1">
              <Sparkles className="w-5 h-5 text-blue-500" />
              <span>Multi-Label AI</span>
            </div>
            <p className="text-sm text-zinc-500 dark:text-zinc-400">
              Semantic classification across 11 standard industry categories.
            </p>
          </div>

          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900">
            <div className="flex items-center space-x-2 text-zinc-800 dark:text-zinc-200 font-semibold mb-1">
              <ShieldCheck className="w-5 h-5 text-emerald-500" />
              <span>Contextual Alerts</span>
            </div>
            <p className="text-sm text-zinc-500 dark:text-zinc-400">
              Direct SMS notifications for critical matches and interviews.
            </p>
          </div>
        </div>

        <div className="pt-6 text-xs text-zinc-400">
          Phase 1 Foundation Initialized &bull; Frontend Ready
        </div>
      </div>
    </main>
  );
}
