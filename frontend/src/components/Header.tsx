import { Shield, RotateCcw, Terminal } from 'lucide-react'

interface HeaderProps {
    onReset?: () => void
}

export default function Header({ onReset }: HeaderProps) {
    return (
        <header className="h-16 flex items-center justify-between px-6 border-b border-dark-800/80 bg-dark-900/50 backdrop-blur-md z-50">
            <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-cyber-500 to-cyber-700 flex items-center justify-center cyber-glow">
                    <Shield className="w-5 h-5 text-white" />
                </div>
                <div>
                    <h1 className="text-sm font-semibold text-gray-100 tracking-wide">
                        Project Analyzer Agent
                    </h1>
                    <p className="text-[10px] text-dark-400 tracking-widest uppercase">
                        智能代码审计 · RAG 对话平台
                    </p>
                </div>
            </div>

            <div className="flex items-center gap-4">
                <div className="hidden sm:flex items-center gap-2 text-[11px] text-dark-400 font-mono">
                    <Terminal className="w-3.5 h-3.5" />
                    <span>v0.1.0</span>
                    <span className="text-dark-600">|</span>
                    <span className="flex items-center gap-1">
                        <span className="w-1.5 h-1.5 rounded-full bg-cyber-500 animate-pulse" />
                        在线
                    </span>
                </div>
                {onReset && (
                    <button
                        onClick={onReset}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-mono
                       text-dark-300 border border-dark-600/40
                       hover:border-cyber-600/50 hover:text-cyber-400
                       transition-all duration-200"
                    >
                        <RotateCcw className="w-3.5 h-3.5" />
                        新分析
                    </button>
                )}
            </div>
        </header>
    )
}
