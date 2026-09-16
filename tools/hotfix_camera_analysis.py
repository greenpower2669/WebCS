from pathlib import Path

path = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/CameraForegroundService.kt")
text = path.read_text(encoding="utf-8")
original = text

replacements = [
    (
'''    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        previewProvider = surfaceProvider
        val existing = previewUseCase
        if (existing != null) {
            existing.setSurfaceProvider(surfaceProvider)
            return
        }
        bindPreviewOnly()
    }
''',
'''    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        previewProvider = surfaceProvider
        // Recrée une session unique Preview + Analysis + VideoCapture.
        // Sur certains Samsung, ajouter Preview dans une seconde liaison CameraX
        // laisse l'aperçu fonctionner mais peut affamer ImageAnalysis.
        if (cameraProvider != null && recording == null) {
            rebindCamera()
        } else {
            previewUseCase?.setSurfaceProvider(surfaceProvider)
        }
    }
'''
    ),
    (
'''        if (latestResult == null || effectiveWidth <= 0) {
            listener?.onStatus("Attends une image caméra valide avant de lancer REC.")
            return
        }
''',
'''        // L'enregistrement vidéo ne doit pas dépendre d'ImageAnalysis.
        // Le Preview peut être valide même si l'analyse est momentanément en reprise.
        if (cameraProvider == null) {
            listener?.onStatus("Caméra pas encore prête pour REC.")
            return
        }
'''
    ),
    (
'''            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    if (effectiveWidth != image.width || effectiveHeight != image.height) {
                        effectiveWidth = image.width
                        effectiveHeight = image.height
                        notifyStatus("Résolution d'analyse effective : ${image.width}×${image.height} · caméra $selectedId")
                    }
                    latestResult = analyse(image)
                    latestResult?.let { consumeCalibration(it) }
                } finally {
                    image.close()
                }
            }
''',
'''            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    if (effectiveWidth != image.width || effectiveHeight != image.height) {
                        effectiveWidth = image.width
                        effectiveHeight = image.height
                        notifyStatus("Analyse prête : ${image.width}×${image.height} · caméra $selectedId")
                    }
                    latestResult = analyse(image)
                    latestResult?.let { consumeCalibration(it) }
                } catch (e: Exception) {
                    latestResult = null
                    notifyStatus("Erreur analyse caméra : ${e.javaClass.simpleName} · ${e.message ?: "sans détail"}")
                } finally {
                    image.close()
                }
            }
'''
    ),
    (
'''            provider.bindToLifecycle(this, selector, analysis, videoCapture)
            bindPreviewOnly()
            listener?.onReady(score, isRecording())
''',
'''            // Une seule liaison CameraX pour éviter le cas où Preview fonctionne
            // alors qu'ImageAnalysis ne reçoit aucune trame.
            val preview = previewProvider?.let { surface ->
                Preview.Builder()
                    .setTargetResolution(requested)
                    .build()
                    .also { it.setSurfaceProvider(surface) }
            }
            previewUseCase = preview
            if (preview != null) {
                provider.bindToLifecycle(this, selector, preview, analysis, videoCapture)
            } else {
                provider.bindToLifecycle(this, selector, analysis, videoCapture)
            }
            listener?.onReady(score, isRecording())
'''
    ),
    (
'''        val result = mutableListOf(QualityOption("AUTO", "Auto (meilleure disponible)"))
''',
'''        val result = mutableListOf(QualityOption("AUTO", "Auto (compatible analyse)"))
'''
    ),
    (
'''        return if (desired != null && supported.contains(desired)) desired else supported.first()
''',
'''        if (desired != null && supported.contains(desired)) return desired
        // AUTO privilégie la stabilité de Preview + ImageAnalysis + VideoCapture.
        // L'utilisateur peut toujours forcer FHD/UHD dans les réglages.
        return when {
            supported.contains(Quality.HD) -> Quality.HD
            supported.contains(Quality.SD) -> Quality.SD
            else -> supported.last()
        }
'''
    ),
]

for old, new in replacements:
    if old not in text:
        raise SystemExit(f"Hotfix pattern not found:\n{old[:180]}")
    text = text.replace(old, new, 1)

if text != original:
    path.write_text(text, encoding="utf-8")
    print("CameraForegroundService.kt patched")
else:
    print("No changes needed")
