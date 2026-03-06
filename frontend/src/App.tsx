import { useState, useCallback } from 'react'
import { Bot, FileCode2, LayoutDashboard, PanelRight, PanelRightClose, Radar, RotateCcw } from 'lucide-react'
import Dashboard from './components/Dashboard'
import BlueprintViewer from './components/BlueprintViewer'
import RagChat from './components/RagChat'
import SnippetReview from './components/SnippetReview'
import { analyzeProjectStream, getProject, type AnalyzeResponse, type AnalyzeStreamStats, type AuditTuningOptions } from './api'

type AppPhase = 'idle' | 'analyzing' | 'done' | 'error'
type AnalyzeMode = 'standard' | 'interactive'
type MainView = 'dashboard' | 'insight' | 'snippet'

export default function App() {
    const initialChatWidth = Number(localStorage.getItem('ui:chatWidth') || '520')
    const [phase, setPhase] = useState<AppPhase>('idle')
    const [progress, setProgress] = useState(0)
    const [progressText, setProgressText] = useState('')
    const [progressLogs, setProgressLogs] = useState<string[]>([])
    const [progressStats, setProgressStats] = useState<AnalyzeStreamStats | null>(null)
    const [blueprint, setBlueprint] = useState('')
    const [errorMsg, setErrorMsg] = useState('')
    const [filesScanned, setFilesScanned] = useState(0)
    const [projectId, setProjectId] = useState('')
    const [reasoning, setReasoning] = useState('')
    const [mainView, setMainView] = useState<MainView>('dashboard')
    const [isChatOpen, setIsChatOpen] = useState(true)
    const [chatWidth, setChatWidth] = useState(Number.isFinite(initialChatWidth) ? Math.max(360, Math.min(860, initialChatWidth)) : 520)

    const handleAnalyze = useCallback(async (path: string, mode: AnalyzeMode, tuning?: AuditTuningOptions) => {
        setPhase('analyzing')
        setProgress(0)
        setProgressText('准备连接分析流...')
        setProgressLogs([])
        setProgressStats(null)
        setErrorMsg('')
        setBlueprint('')
        setReasoning('')

        try {
            const data: AnalyzeResponse = await analyzeProjectStream(
                path,
                mode,
                (pct, text) => {
                    setProgress(pct)
                    setProgressText(text)
                    setProgressLogs((prev) => {
                        if (prev[prev.length - 1] === text) return prev
                        return [...prev, text].slice(-20)
                    })
                },
                (logLine) => {
                    setProgressLogs((prev) => {
                        if (!logLine || prev[prev.length - 1] === logLine) return prev
                        return [...prev, logLine].slice(-20)
                    })
                },
                (stats) => {
                    setProgressStats(stats)
                },
                tuning,
            )

            const pid = data.projectId || ''
            setProjectId(pid)
            if (pid) localStorage.setItem('projectId', pid)

            setProgress(100)
            setProgressText(data.alreadyIndexed ? '已存在索引，加载完成 ✓' : '分析完成 ✓')
            setBlueprint(data.auditReport || data.blueprint || '')
            setReasoning(data.reasoning || '')
            setFilesScanned(data.filesScanned || 0)
            setTimeout(() => {
                setPhase('done')
                setMainView('insight')
            }, 600)
        } catch (err: any) {
            setPhase('error')
            setErrorMsg(err.message || '未知错误')
        }
    }, [])

    const handleOpenProject = useCallback(async (pid: string) => {
        setPhase('analyzing')
        setProgress(30)
        setProgressText('加载已索引项目...')
        setErrorMsg('')
        try {
            const detail = await getProject(pid)
            setProjectId(detail.projectId)
            localStorage.setItem('projectId', detail.projectId)
            setBlueprint(detail.blueprint || '')
            setReasoning('')
            setFilesScanned(detail.meta?.filesScanned || 0)
            setProgress(100)
            setProgressText('加载完成 ✓')
            setTimeout(() => {
                setPhase('done')
                setMainView('insight')
            }, 300)
        } catch (err: any) {
            setPhase('error')
            setErrorMsg(err.message || '加载失败')
        }
    }, [])

    const handleReset = useCallback(() => {
        setPhase('idle')
        setProgress(0)
        setProgressText('')
        setProgressLogs([])
        setProgressStats(null)
        setBlueprint('')
        setErrorMsg('')
        setFilesScanned(0)
        setProjectId('')
        setReasoning('')
        setMainView('dashboard')
    }, [])

    const handleProjectDeleted = useCallback((deletedProjectId: string) => {
        if (deletedProjectId !== projectId) return
        handleReset()
    }, [projectId, handleReset])

    const canOpenInsight = phase === 'done' && blueprint.trim().length > 0

    const startResizeChat = useCallback((event: React.MouseEvent<HTMLDivElement>) => {
        event.preventDefault()
        let latest = chatWidth
        const onMove = (moveEvent: MouseEvent) => {
            const next = Math.max(360, Math.min(900, window.innerWidth - moveEvent.clientX))
            latest = next
            setChatWidth(next)
        }
        const onUp = () => {
            localStorage.setItem('ui:chatWidth', String(latest))
            window.removeEventListener('mousemove', onMove)
            window.removeEventListener('mouseup', onUp)
        }
        window.addEventListener('mousemove', onMove)
        window.addEventListener('mouseup', onUp)
    }, [chatWidth])

    return (
        <div className="h-screen bg-dark-950 text-gray-100 flex">
            {/* make main area scrollable rather than hiding overflow globally */}
            <aside className="w-16 sm:w-20 border-r border-dark-800/80 bg-dark-900/70 backdrop-blur-xl flex flex-col items-center py-4 gap-4">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-cyber-500 to-cyber-700 flex items-center justify-center cyber-glow">
                    <Bot className="w-5 h-5 text-white" />
                </div>
                <NavButton icon={<LayoutDashboard className="w-4 h-4" />} active={mainView === 'dashboard'} onClick={() => setMainView('dashboard')} title="Dashboard" />
                <NavButton icon={<Radar className="w-4 h-4" />} active={mainView === 'insight'} onClick={() => canOpenInsight && setMainView('insight')} title={canOpenInsight ? 'Insight' : 'Insight(待生成)'} disabled={!canOpenInsight} />
                <NavButton icon={<FileCode2 className="w-4 h-4" />} active={mainView === 'snippet'} onClick={() => setMainView('snippet')} title="Snippet" />
                <div className="flex-1" />
                <NavButton icon={isChatOpen ? <PanelRightClose className="w-4 h-4" /> : <PanelRight className="w-4 h-4" />} active={isChatOpen} onClick={() => setIsChatOpen((value) => !value)} title={isChatOpen ? '收起聊天' : '打开聊天'} />
                {phase === 'done' && (
                    <NavButton icon={<RotateCcw className="w-4 h-4" />} active={false} onClick={handleReset} title="新分析" />
                )}
            </aside>

            <main className="flex-1 min-w-0 flex flex-col overflow-auto">
                <header className="h-14 border-b border-dark-800/70 bg-dark-900/40 backdrop-blur-md px-5 flex items-center justify-between">
                    <div>
                        <h1 className="text-sm font-semibold">Project Analyzer Agent</h1>
                        <p className="text-[10px] text-dark-400 font-mono">高级控制台 · 审计 / 洞察 / RAG</p>
                    </div>
                    <div className="text-[11px] text-dark-400 font-mono">{projectId ? `Project: ${projectId.slice(0, 8)}...` : '未选择项目'}</div>
                </header>

                {mainView === 'dashboard' && (
                    <div className="flex-1 min-h-0 animate-fade-in">
                        <Dashboard
                            phase={phase}
                            progress={progress}
                            progressText={progressText}
                            progressLogs={progressLogs}
                            progressStats={progressStats}
                            errorMsg={errorMsg}
                            onAnalyze={handleAnalyze}
                            onOpenProject={handleOpenProject}
                            onProjectDeleted={handleProjectDeleted}
                        />
                    </div>
                )}

                {mainView === 'insight' && (
                    <div className="flex-1 min-h-0 p-3 animate-fade-in">
                        {canOpenInsight ? (
                            <div className="h-full glass-panel overflow-hidden">
                                <BlueprintViewer content={blueprint} filesScanned={filesScanned} reasoning={reasoning} />
                            </div>
                        ) : (
                            <div className="h-full flex items-center justify-center text-sm text-dark-400">先完成一次项目分析，再查看 Insight 视图</div>
                        )}
                    </div>
                )}

                {mainView === 'snippet' && (
                    <div className="flex-1 min-h-0 animate-fade-in">
                        <SnippetReview />
                    </div>
                )}
            </main>

            {isChatOpen && (
                <aside className="relative border-l border-dark-800/80 bg-dark-900/60 backdrop-blur-xl overflow-hidden transition-all duration-150" style={{ width: `${chatWidth}px` }}>
                    <div
                        onMouseDown={startResizeChat}
                        className="absolute left-0 top-0 h-full w-1 cursor-col-resize bg-transparent hover:bg-cyber-500/30"
                        title="拖拽调整对话面板宽度"
                    />
                    <div className="h-full" style={{ width: `${chatWidth}px` }}>
                        {projectId ? (
                            <RagChat projectId={projectId} />
                        ) : (
                            <div className="h-full flex items-center justify-center text-sm text-dark-400 px-6 text-center">
                                先在 Dashboard 分析项目或打开历史项目，然后即可在此开启流式 RAG 对话。
                            </div>
                        )}
                    </div>
                </aside>
            )}
        </div>
    )
}

function NavButton({ icon, active, onClick, title, disabled }: { icon: React.ReactNode; active: boolean; onClick: () => void; title: string; disabled?: boolean }) {
    return (
        <button
            type="button"
            disabled={disabled}
            onClick={onClick}
            title={title}
            className={`w-10 h-10 rounded-lg border transition-colors flex items-center justify-center ${active
                ? 'bg-cyber-600/20 border-cyber-600/50 text-cyber-400'
                : 'bg-dark-900/70 border-dark-700/70 text-dark-400 hover:text-gray-200 hover:border-dark-600'
                } disabled:opacity-40 disabled:hover:border-dark-700/70`}
        >
            {icon}
        </button>
    )
}
