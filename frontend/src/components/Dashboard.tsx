import { useState, useCallback } from 'react'
import { Search, Loader2, AlertTriangle, Folder, FolderOpen, File, ChevronRight, X, FolderSearch, Cpu, Zap } from 'lucide-react'

interface DashboardProps {
    phase: 'idle' | 'analyzing' | 'error'
    progress: number
    progressText: string
    errorMsg: string
    onAnalyze: (path: string) => void
}

interface BrowseEntry {
    name: string
    path: string
    type: 'dir' | 'file'
}

interface BrowseResult {
    current: string
    parent: string | null
    entries: BrowseEntry[]
}

const API_BASE = '/api'

export default function Dashboard({ phase, progress, progressText, errorMsg, onAnalyze }: DashboardProps) {
    const [path, setPath] = useState('/home/cqy/project-analyzer-agent')
    const [showBrowser, setShowBrowser] = useState(false)
    const [browseData, setBrowseData] = useState<BrowseResult | null>(null)
    const [browseLoading, setBrowseLoading] = useState(false)
    const [browseError, setBrowseError] = useState('')

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault()
        if (path.trim() && phase !== 'analyzing') {
            onAnalyze(path.trim())
        }
    }

    const browseTo = useCallback(async (targetPath: string) => {
        setBrowseLoading(true)
        setBrowseError('')
        try {
            const res = await fetch(`${API_BASE}/browse?path=${encodeURIComponent(targetPath)}`)
            const data = await res.json()
            if (!res.ok) throw new Error(data.error || '浏览失败')
            setBrowseData(data)
        } catch (err: any) {
            setBrowseError(err.message)
        } finally {
            setBrowseLoading(false)
        }
    }, [])

    const openBrowser = () => {
        setShowBrowser(true)
        browseTo(path || '/home')
    }

    const selectDir = (dirPath: string) => {
        setPath(dirPath)
        setShowBrowser(false)
    }

    return (
        <div className="flex-1 flex items-center justify-center px-4 py-12">
            <div className="w-full max-w-2xl animate-fade-in">
                {/* Hero */}
                <div className="text-center mb-10">
                    <div className="inline-flex items-center justify-center w-20 h-20 rounded-2xl bg-gradient-to-br from-cyber-600/20 to-cyber-800/20 border border-cyber-700/30 mb-6 cyber-glow">
                        <Cpu className="w-10 h-10 text-cyber-400" />
                    </div>
                    <h2 className="text-3xl font-bold text-gray-100 mb-3 tracking-tight">
                        代码<span className="text-cyber-400">深度审计</span>引擎
                    </h2>
                    <p className="text-sm text-dark-400 max-w-md mx-auto leading-relaxed">
                        输入项目路径，AI 将自动扫描代码、生成架构审计报告，并构建 RAG 知识库以供实时问答。
                    </p>
                </div>

                {/* 路径输入 + 文件夹浏览按钮 */}
                <form onSubmit={handleSubmit} className="mb-6">
                    <div className="glass-panel p-2 flex items-center gap-2 cyber-glow">
                        <div className="flex items-center gap-2 pl-3 text-dark-400">
                            <FolderSearch className="w-5 h-5 text-cyber-500/70" />
                        </div>
                        <input
                            type="text"
                            value={path}
                            onChange={(e) => setPath(e.target.value)}
                            placeholder="/home/user/my-project"
                            disabled={phase === 'analyzing'}
                            className="flex-1 bg-transparent border-none outline-none text-sm text-gray-200
                         placeholder:text-dark-500 font-mono py-2 disabled:opacity-50"
                        />
                        {/* 文件夹浏览按钮 */}
                        <button
                            type="button"
                            onClick={openBrowser}
                            disabled={phase === 'analyzing'}
                            title="浏览服务器目录"
                            className="w-9 h-9 rounded-lg border border-dark-600 hover:border-cyber-600 hover:bg-cyber-600/10
                                       flex items-center justify-center transition-colors duration-200 disabled:opacity-40"
                        >
                            <Folder className="w-4 h-4 text-dark-400 hover:text-cyber-400" />
                        </button>
                        <button
                            type="submit"
                            disabled={phase === 'analyzing' || !path.trim()}
                            className="cyber-btn flex items-center gap-2"
                        >
                            {phase === 'analyzing' ? (
                                <>
                                    <Loader2 className="w-4 h-4 animate-spin" />
                                    审计中
                                </>
                            ) : (
                                <>
                                    <Zap className="w-4 h-4" />
                                    开始审计
                                </>
                            )}
                        </button>
                    </div>
                </form>

                {/* 进度条 */}
                {phase === 'analyzing' && (
                    <div className="glass-panel p-5 animate-slide-up">
                        <div className="flex items-center justify-between mb-3">
                            <span className="text-xs text-dark-300 font-mono">{progressText}</span>
                            <span className="text-xs text-cyber-400 font-mono font-semibold">{progress}%</span>
                        </div>
                        <div className="w-full h-2 bg-dark-900 rounded-full overflow-hidden">
                            <div
                                className="h-full bg-gradient-to-r from-cyber-600 to-cyber-400 rounded-full transition-all duration-700 ease-out relative"
                                style={{ width: `${progress}%` }}
                            >
                                <div className="absolute inset-0 bg-gradient-to-r from-transparent via-white/20 to-transparent animate-scan-line" />
                            </div>
                        </div>
                        <div className="flex items-center gap-6 mt-4 text-[11px] text-dark-400 font-mono">
                            <StageIndicator label="扫描文件" active={progress >= 10} done={progress >= 45} />
                            <StageIndicator label="AI 审计" active={progress >= 45} done={progress >= 75} />
                            <StageIndicator label="RAG 索引" active={progress >= 75} done={progress >= 100} />
                        </div>
                    </div>
                )}

                {/* 错误提示 */}
                {phase === 'error' && (
                    <div className="glass-panel p-5 border-red-500/30 animate-slide-up">
                        <div className="flex items-start gap-3">
                            <AlertTriangle className="w-5 h-5 text-red-400 mt-0.5 flex-shrink-0" />
                            <div>
                                <p className="text-sm font-medium text-red-300 mb-1">分析失败</p>
                                <p className="text-xs text-dark-400 font-mono break-all">{errorMsg}</p>
                            </div>
                        </div>
                    </div>
                )}

                {/* 功能标签 */}
                <div className="flex flex-wrap justify-center gap-2 mt-8">
                    {['多语言扫描', 'DeepSeek 审计', '向量化索引', 'RAG 流式对话', 'Mermaid 可视化'].map((tag) => (
                        <span key={tag} className="tag-chip cursor-default">
                            <Search className="w-3 h-3" />
                            {tag}
                        </span>
                    ))}
                </div>
            </div>

            {/* 目录浏览器弹窗 */}
            {showBrowser && (
                <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm animate-fade-in">
                    <div className="w-full max-w-lg glass-panel flex flex-col" style={{ maxHeight: '70vh' }}>
                        {/* 弹窗头部 */}
                        <div className="flex items-center justify-between px-4 py-3 border-b border-dark-700/50 flex-shrink-0">
                            <div className="flex items-center gap-2">
                                <FolderOpen className="w-4 h-4 text-cyber-500" />
                                <span className="text-sm font-semibold text-gray-200">选择项目目录</span>
                            </div>
                            <button
                                onClick={() => setShowBrowser(false)}
                                className="w-7 h-7 rounded-lg hover:bg-dark-700/60 flex items-center justify-center transition-colors"
                            >
                                <X className="w-4 h-4 text-dark-400" />
                            </button>
                        </div>

                        {/* 当前路径面包屑 */}
                        {browseData && (
                            <div className="px-4 py-2 flex items-center gap-1 text-xs font-mono text-dark-400 border-b border-dark-700/30 flex-shrink-0 overflow-x-auto whitespace-nowrap">
                                {browseData.parent !== null && (
                                    <button
                                        onClick={() => browseTo(browseData.parent!)}
                                        className="text-cyber-500/70 hover:text-cyber-400 transition-colors"
                                    >
                                        ..
                                    </button>
                                )}
                                {browseData.parent !== null && <ChevronRight className="w-3 h-3 flex-shrink-0" />}
                                <span className="text-dark-300">{browseData.current}</span>
                            </div>
                        )}

                        {/* 目录列表 */}
                        <div className="flex-1 overflow-y-auto">
                            {browseLoading && (
                                <div className="flex items-center justify-center py-8">
                                    <Loader2 className="w-5 h-5 text-cyber-500 animate-spin" />
                                </div>
                            )}
                            {browseError && (
                                <div className="px-4 py-3 text-xs text-red-400 font-mono">{browseError}</div>
                            )}
                            {!browseLoading && browseData && (
                                <div>
                                    {browseData.entries.length === 0 && (
                                        <p className="px-4 py-3 text-xs text-dark-500">空目录</p>
                                    )}
                                    {browseData.entries.map((entry) => (
                                        <button
                                            key={entry.path}
                                            onClick={() => entry.type === 'dir' ? browseTo(entry.path) : undefined}
                                            disabled={entry.type === 'file'}
                                            className={`w-full flex items-center gap-3 px-4 py-2.5 text-left transition-colors
                                                ${entry.type === 'dir'
                                                    ? 'hover:bg-dark-700/40 cursor-pointer'
                                                    : 'opacity-40 cursor-default'}`}
                                        >
                                            {entry.type === 'dir'
                                                ? <Folder className="w-4 h-4 text-cyber-500/70 flex-shrink-0" />
                                                : <File className="w-4 h-4 text-dark-500 flex-shrink-0" />
                                            }
                                            <span className={`text-sm font-mono truncate ${entry.type === 'dir' ? 'text-gray-300' : 'text-dark-400'}`}>
                                                {entry.name}
                                            </span>
                                        </button>
                                    ))}
                                </div>
                            )}
                        </div>

                        {/* 选择按钮 */}
                        {browseData && (
                            <div className="px-4 py-3 border-t border-dark-700/50 flex justify-between items-center flex-shrink-0">
                                <span className="text-[11px] text-dark-500 font-mono truncate max-w-[60%]">
                                    {browseData.current}
                                </span>
                                <button
                                    onClick={() => selectDir(browseData.current)}
                                    className="cyber-btn text-xs"
                                >
                                    选择此目录
                                </button>
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    )
}

function StageIndicator({ label, active, done }: { label: string; active: boolean; done: boolean }) {
    return (
        <div className="flex items-center gap-1.5">
            <div className={`w-2 h-2 rounded-full transition-colors duration-300 ${done ? 'bg-cyber-400' : active ? 'bg-cyber-600 animate-pulse' : 'bg-dark-600'
                }`} />
            <span className={done ? 'text-cyber-400' : active ? 'text-dark-300' : 'text-dark-600'}>
                {label}
            </span>
        </div>
    )
}
