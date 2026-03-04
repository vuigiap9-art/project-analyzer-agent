import { useState, useCallback } from 'react'
import Header from './components/Header'
import Dashboard from './components/Dashboard'
import BlueprintViewer from './components/BlueprintViewer'
import RagChat from './components/RagChat'
import { analyzeProject, getProject, type AnalyzeResponse } from './api'

type AppPhase = 'idle' | 'analyzing' | 'done' | 'error'

export default function App() {
    const [phase, setPhase] = useState<AppPhase>('idle')
    const [progress, setProgress] = useState(0)
    const [progressText, setProgressText] = useState('')
    const [blueprint, setBlueprint] = useState('')
    const [errorMsg, setErrorMsg] = useState('')
    const [filesScanned, setFilesScanned] = useState(0)
    const [projectId, setProjectId] = useState<string>('')

    const handleAnalyze = useCallback(async (path: string) => {
        setPhase('analyzing')
        setProgress(0)
        setErrorMsg('')
        setBlueprint('')

        // 延迟启动“假进度条”：如果项目已索引，后端会很快返回，不需要演戏
        const stages = [
            { pct: 10, text: '连接后端服务...' },
            { pct: 25, text: '扫描项目文件...' },
            { pct: 45, text: '提取代码片段...' },
            { pct: 60, text: '调用 DeepSeek 审计中...' },
            { pct: 75, text: 'AI 生成审计报告...' },
            { pct: 85, text: '向量化索引（RAG）...' },
        ]

        let timer: number | null = null
        let stageIndex = 0
        const startProgressTimer = () => {
            if (timer != null) return
            timer = window.setInterval(() => {
                if (stageIndex < stages.length) {
                    setProgress(stages[stageIndex].pct)
                    setProgressText(stages[stageIndex].text)
                    stageIndex++
                }
            }, 2000)
        }

        const timerGuard = window.setTimeout(() => startProgressTimer(), 800)

        try {
            const data: AnalyzeResponse = await analyzeProject(path)
            window.clearTimeout(timerGuard)
            if (timer != null) window.clearInterval(timer)

            const pid = data.projectId || ''
            setProjectId(pid)
            if (pid) localStorage.setItem('projectId', pid)

            setProgress(100)
            setProgressText(data.alreadyIndexed ? '已存在索引，加载完成 ✓' : '分析完成 ✓')
            setBlueprint(data.auditReport || data.blueprint || '')
            setFilesScanned(data.filesScanned || 0)
            setTimeout(() => setPhase('done'), 600)
        } catch (err: any) {
            window.clearTimeout(timerGuard)
            if (timer != null) window.clearInterval(timer)
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
            setFilesScanned(detail.meta?.filesScanned || 0)
            setProgress(100)
            setProgressText('加载完成 ✓')
            setTimeout(() => setPhase('done'), 300)
        } catch (err: any) {
            setPhase('error')
            setErrorMsg(err.message || '加载失败')
        }
    }, [])

    const handleReset = useCallback(() => {
        setPhase('idle')
        setProgress(0)
        setProgressText('')
        setBlueprint('')
        setErrorMsg('')
        setFilesScanned(0)
        setProjectId('')
    }, [])

    return (
        <div className="min-h-screen bg-dark-950 flex flex-col">
            <Header onReset={phase === 'done' ? handleReset : undefined} />

            <main className="flex-1 flex flex-col">
                {(phase === 'idle' || phase === 'analyzing' || phase === 'error') && (
                    <Dashboard
                        phase={phase}
                        progress={progress}
                        progressText={progressText}
                        errorMsg={errorMsg}
                        onAnalyze={handleAnalyze}
                        onOpenProject={handleOpenProject}
                    />
                )}

                {phase === 'done' && (
                    <div className="flex-1 flex flex-col lg:flex-row gap-0 lg:gap-1 h-[calc(100vh-64px)]">
                        {/* 左：审计报告 */}
                        <div className="lg:w-1/2 h-1/2 lg:h-full overflow-hidden flex flex-col">
                            <BlueprintViewer
                                content={blueprint}
                                filesScanned={filesScanned}
                            />
                        </div>
                        {/* 右：RAG 对话 */}
                        <div className="lg:w-1/2 h-1/2 lg:h-full overflow-hidden flex flex-col">
                            <RagChat projectId={projectId} />
                        </div>
                    </div>
                )}
            </main>
        </div>
    )
}
