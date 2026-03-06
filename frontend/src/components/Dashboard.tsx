import { useEffect, useState, useCallback } from 'react'
import { Search, Loader2, AlertTriangle, Folder, FolderOpen, File, ChevronRight, X, FolderSearch, Cpu, Zap } from 'lucide-react'
import { browsePath, deleteProject, listProjects, type ProjectMeta, type BrowseResult, type AnalyzeStreamStats, type AuditTuningOptions } from '../api'

interface DashboardProps {
    phase: 'idle' | 'analyzing' | 'error'
    progress: number
    progressText: string
    progressLogs: string[]
    progressStats: AnalyzeStreamStats | null
    errorMsg: string
    onAnalyze: (path: string, mode: 'standard' | 'interactive', tuning?: AuditTuningOptions) => void
    onOpenProject: (projectId: string) => void
    onProjectDeleted?: (projectId: string) => void
}

export default function Dashboard({ phase, progress, progressText, progressLogs, progressStats, errorMsg, onAnalyze, onOpenProject, onProjectDeleted }: DashboardProps) {
    const [path, setPath] = useState('/home/cqy/project-analyzer-agent')
    const [mode, setMode] = useState<'standard' | 'interactive'>('standard')
    const [showBrowser, setShowBrowser] = useState(false)
    const [browseData, setBrowseData] = useState<BrowseResult | null>(null)
    const [browseLoading, setBrowseLoading] = useState(false)
    const [browseError, setBrowseError] = useState('')
    const [projects, setProjects] = useState<ProjectMeta[]>([])
    const [projectsError, setProjectsError] = useState('')
    const [maxCalls, setMaxCalls] = useState(32)
    const [maxVisitedFiles, setMaxVisitedFiles] = useState(80)
    const [maxTotalReadLines, setMaxTotalReadLines] = useState(4800)
    const [maxLinesPerCall, setMaxLinesPerCall] = useState(200)
    const [targetLinesPerCall, setTargetLinesPerCall] = useState(150)
    const [minLinesPerCall, setMinLinesPerCall] = useState(100)

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault()
        if (path.trim() && phase !== 'analyzing') {
            const tuning = mode === 'interactive'
                ? {
                    maxCalls,
                    maxVisitedFiles,
                    maxTotalReadLines,
                    maxLinesPerCall,
                    targetLinesPerCall,
                    minLinesPerCall,
                }
                : undefined
            onAnalyze(path.trim(), mode, tuning)
        }
    }

    const browseTo = useCallback(async (targetPath: string) => {
        setBrowseLoading(true)
        setBrowseError('')
        try {
            const data = await browsePath(targetPath)
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

    const handleDeleteProject = async (projectId: string) => {
        if (!window.confirm(`确认删除项目 ${projectId} 的索引与历史记录？`)) return
        try {
            await deleteProject(projectId)
            setProjects((prev) => prev.filter((item) => item.projectId !== projectId))
            onProjectDeleted?.(projectId)
        } catch (err: any) {
            setProjectsError(err?.message || '删除项目失败')
        }
    }

    useEffect(() => {
        let cancelled = false
        listProjects()
            .then((data) => { if (!cancelled) setProjects(data || []) })
            .catch((e) => { if (!cancelled) setProjectsError(String(e?.message || e)) })
        return () => { cancelled = true }
    }, [])

    return (
        <div className="flex-1 flex items-center justify-center px-4 py-12 overflow-auto">
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

                {/* 模式选择：标准审计 / 交互式审计 */}
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <div className="inline-flex p-1 rounded-xl bg-dark-900/80 border border-dark-700/70">
                        <button
                            type="button"
                            onClick={() => mode !== 'standard' && setMode('standard')}
                            className={`px-3 py-1.5 text-[11px] font-mono rounded-lg transition-colors ${
                                mode === 'standard'
                                    ? 'bg-cyber-600 text-white'
                                    : 'text-dark-400 hover:text-gray-100'
                            }`}
                            disabled={phase === 'analyzing'}
                        >
                            标准审计（含 RAG 索引）
                        </button>
                        <button
                            type="button"
                            onClick={() => mode !== 'interactive' && setMode('interactive')}
                            className={`ml-1 px-3 py-1.5 text-[11px] font-mono rounded-lg transition-colors whitespace-nowrap ${
                                mode === 'interactive'
                                    ? 'bg-cyber-600 text-white'
                                    : 'text-dark-400 hover:text-gray-100'
                            }`}
                            disabled={phase === 'analyzing'}
                        >
                            交互式审计（逐步拉取代码）
                        </button>
                    </div>
                    <p className="text-[11px] text-dark-500 font-mono">
                        {mode === 'standard'
                            ? '一次性扫描 + 审计 + 构建向量索引，可配合右侧 RAG 问答使用'
                            : 'DeepSeek 基于 Project-Map 主动点名查看文件，避免上下文爆炸'}
                    </p>
                </div>

                {mode === 'interactive' && (
                    <div className="glass-panel p-3 mb-4 border-dark-700/60">
                        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                            <TuningInput label="最大调用" value={maxCalls} onChange={setMaxCalls} disabled={phase === 'analyzing'} min={1} />
                            <TuningInput label="文件预算" value={maxVisitedFiles} onChange={setMaxVisitedFiles} disabled={phase === 'analyzing'} min={1} />
                            <TuningInput label="总行预算" value={maxTotalReadLines} onChange={setMaxTotalReadLines} disabled={phase === 'analyzing'} min={200} />
                            <TuningInput label="单次上限" value={maxLinesPerCall} onChange={setMaxLinesPerCall} disabled={phase === 'analyzing'} min={20} />
                            <TuningInput label="目标读行" value={targetLinesPerCall} onChange={setTargetLinesPerCall} disabled={phase === 'analyzing'} min={20} />
                            <TuningInput label="最小读行" value={minLinesPerCall} onChange={setMinLinesPerCall} disabled={phase === 'analyzing'} min={1} />
                        </div>
                        <p className="mt-2 text-[10px] text-dark-500 font-mono">
                            低 token 建议：提高“目标读行”，并降低“最大调用/总行预算”。
                        </p>
                    </div>
                )}

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

                        {progressStats && (
                            <div className="mt-4 grid grid-cols-2 sm:grid-cols-4 gap-2">
                                <StatCard label="调用次数" value={`${progressStats.calls}/${progressStats.maxCalls}`} />
                                <StatCard label="已读文件" value={`${progressStats.visitedFiles}/${progressStats.maxVisitedFiles}`} />
                                <StatCard label="剩余调用" value={`${progressStats.remainingCalls}`} />
                                <StatCard label="剩余文件" value={`${progressStats.remainingVisitedFiles}`} />
                                <StatCard label="剩余行预算" value={`${progressStats.remainingLines}`} />
                            </div>
                        )}

                        {progressLogs.length > 0 && (
                            <div className="mt-4 rounded-lg border border-dark-700/50 bg-dark-950/70 p-3 max-h-36 overflow-y-auto">
                                <p className="text-[10px] text-dark-500 font-mono mb-2">analysis.stream.log</p>
                                <div className="space-y-1">
                                    {progressLogs.map((log, idx) => (
                                        <p key={`${idx}-${log}`} className="text-[11px] text-dark-300 font-mono break-all">
                                            &gt; {log}
                                        </p>
                                    ))}
                                </div>
                            </div>
                        )}
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

                {/* 已索引项目列表 */}
                <div className="mt-8">
                    <div className="flex items-center justify-between mb-2">
                        <p className="text-xs text-dark-400 font-mono">已索引项目</p>
                        {projects.length > 0 && (
                            <p className="text-[10px] text-dark-500 font-mono">{projects.length} 个</p>
                        )}
                    </div>
                    {projectsError && (
                        <div className="text-[11px] text-red-400 font-mono">{projectsError}</div>
                    )}
                    {projects.length === 0 && !projectsError && (
                        <div className="text-[11px] text-dark-600 font-mono">暂无（先分析一次项目即可出现在这里）</div>
                    )}
                    {projects.length > 0 && (
                        <div className="glass-panel p-3 space-y-2">
                            {projects
                                .slice()
                                .sort((a, b) => (b.indexedAt || '').localeCompare(a.indexedAt || ''))
                                .slice(0, 6)
                                .map((p) => (
                                    <div key={p.projectId} className="flex items-center justify-between gap-3">
                                        <div className="min-w-0">
                                            <p className="text-xs text-gray-200 font-mono truncate">{p.rootPath}</p>
                                            <p className="text-[10px] text-dark-500 font-mono truncate">
                                                {p.projectId} · {p.filesScanned} files · {new Date(p.indexedAt).toLocaleString('zh-CN')}
                                            </p>
                                        </div>
                                        <button
                                            type="button"
                                            disabled={phase === 'analyzing'}
                                            onClick={() => onOpenProject(p.projectId)}
                                            className="cyber-btn text-xs flex-shrink-0 disabled:opacity-40"
                                        >
                                            进入
                                        </button>
                                        <button
                                            type="button"
                                            disabled={phase === 'analyzing'}
                                            onClick={() => handleDeleteProject(p.projectId)}
                                            className="px-3 py-2 text-xs font-mono rounded-lg border border-red-500/40 text-red-300 hover:bg-red-500/10 disabled:opacity-40"
                                        >
                                            删除
                                        </button>
                                    </div>
                                ))}
                        </div>
                    )}
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

function StatCard({ label, value }: { label: string; value: string }) {
    return (
        <div className="rounded-lg border border-dark-700/60 bg-dark-900/70 px-3 py-2">
            <p className="text-[10px] text-dark-500 font-mono">{label}</p>
            <p className="text-sm text-cyber-300 font-mono mt-1">{value}</p>
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

function TuningInput({
    label,
    value,
    onChange,
    disabled,
    min,
}: {
    label: string
    value: number
    onChange: (value: number) => void
    disabled: boolean
    min: number
}) {
    return (
        <label className="rounded-lg border border-dark-700/60 bg-dark-900/70 px-2 py-2">
            <p className="text-[10px] text-dark-500 font-mono">{label}</p>
            <input
                type="number"
                min={min}
                value={value}
                disabled={disabled}
                onChange={(e) => onChange(Math.max(min, Number(e.target.value) || min))}
                className="mt-1 w-full bg-transparent outline-none text-sm text-cyber-300 font-mono disabled:opacity-60"
            />
        </label>
    )
}
