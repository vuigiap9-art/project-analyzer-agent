import { useState, useCallback } from 'react'
import Header from './components/Header'
import Dashboard from './components/Dashboard'
import BlueprintViewer from './components/BlueprintViewer'
import RagChat from './components/RagChat'
import { analyzeProject, type AnalyzeResponse } from './api'

type AppPhase = 'idle' | 'analyzing' | 'done' | 'error'

export default function App() {
    const [phase, setPhase] = useState<AppPhase>('idle')
    const [progress, setProgress] = useState(0)
    const [progressText, setProgressText] = useState('')
    const [blueprint, setBlueprint] = useState('')
    const [errorMsg, setErrorMsg] = useState('')
    const [filesScanned, setFilesScanned] = useState(0)

    const handleAnalyze = useCallback(async (path: string) => {
        setPhase('analyzing')
        setProgress(0)
        setErrorMsg('')
        setBlueprint('')

        // 模拟进度推进
        const stages = [
            { pct: 10, text: '连接后端服务...' },
            { pct: 25, text: '扫描项目文件...' },
            { pct: 45, text: '提取代码片段...' },
            { pct: 60, text: '调用 DeepSeek 审计中...' },
            { pct: 75, text: 'AI 生成审计报告...' },
            { pct: 85, text: '向量化索引（RAG）...' },
        ]

        let stageIndex = 0
        const timer = setInterval(() => {
            if (stageIndex < stages.length) {
                setProgress(stages[stageIndex].pct)
                setProgressText(stages[stageIndex].text)
                stageIndex++
            }
        }, 2000)

        try {
            const data: AnalyzeResponse = await analyzeProject(path)
            clearInterval(timer)
            setProgress(100)
            setProgressText('分析完成 ✓')
            setBlueprint(data.auditReport || data.blueprint || '')
            setFilesScanned(data.filesScanned || 0)
            setTimeout(() => setPhase('done'), 600)
        } catch (err: any) {
            clearInterval(timer)
            setPhase('error')
            setErrorMsg(err.message || '未知错误')
        }
    }, [])

    const handleReset = useCallback(() => {
        setPhase('idle')
        setProgress(0)
        setProgressText('')
        setBlueprint('')
        setErrorMsg('')
        setFilesScanned(0)
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
                            <RagChat />
                        </div>
                    </div>
                )}
            </main>
        </div>
    )
}
