import { createFileRoute } from '@tanstack/react-router'
import { useEffect } from 'react'
import { useNavigate } from '@tanstack/react-router'

export const Route = createFileRoute('/')({
    component: Index,
})

function Index() {
    const navigate = useNavigate()

    useEffect(() => {
        // 默认跳转到 analyzer 以供选择或扫描项目
        navigate({ to: '/analyzer' })
    }, [navigate])

    return (
        <div className="flex items-center justify-center h-full w-full">
            <div className="animate-pulse flex items-center gap-3 text-muted-foreground">
                <div className="w-2 h-2 rounded-full bg-primary animate-ping"></div>
                <span>Initializing Workspace...</span>
            </div>
        </div>
    )
}
