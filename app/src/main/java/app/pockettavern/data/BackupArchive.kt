package app.pockettavern.data

import app.pockettavern.domain.*
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupArchive {
    private const val MAX_TOTAL=128L*1024*1024
    fun write(data: AppData, filesDir: File, target: File) {
        val sources=linkedMapOf<String,File>()
        val mediaRoot=File(filesDir,"media").canonicalFile
        val portable=data.copy(mediaJobs=data.mediaJobs.map { job ->
            val file=File(job.filePath)
            if(job.filePath.isNotBlank() && file.isFile && file.canonicalPath.startsWith(mediaRoot.path+File.separator)) {
                val name="media/${file.name}"; sources[name]=file; job.copy(filePath=name)
            } else job.copy(filePath="")
        })
        val workspace=File(filesDir,"workspace")
        if(workspace.exists()) workspace.walkTopDown().filter { it.isFile }.forEach { file ->
            require(file.canonicalPath.startsWith(workspace.canonicalPath+File.separator)) { "工作区包含外部链接" }
            sources["workspace/"+file.relativeTo(workspace).invariantSeparatorsPath]=file
        }
        val state=DataCodec.encode(portable).toByteArray(Charsets.UTF_8)
        require(sources.size<2000 && sources.values.sumOf { it.length() }+state.size<=MAX_TOTAL) { "备份超过 128 MB，请先单独保存大型媒体" }
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("state.json")); zip.write(state); zip.closeEntry()
            sources.forEach { (name,file) -> zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
        }
    }
    fun restore(bytes: ByteArray, filesDir: File, cacheDir: File): AppData {
        if(bytes.size<4 || bytes[0]!=0x50.toByte() || bytes[1]!=0x4b.toByte()) return DataCodec.decode(bytes.toString(Charsets.UTF_8))
        val stage=File(cacheDir,"restore-${newId()}").apply { mkdirs() }
        try {
            var total=0L; var count=0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(++count<=2000) { "备份文件过多" }
                    val name=entry.name.replace('\\','/')
                    require(!name.startsWith('/') && ':' !in name && name.split('/').none { it==".." || it=="." }) { "备份路径不安全" }
                    require(name=="state.json" || name.startsWith("media/") || name.startsWith("workspace/")) { "备份包含未知文件" }
                    val file=File(stage,name).canonicalFile
                    require(file.path.startsWith(stage.canonicalPath+File.separator)) { "备份路径越界" }
                    if(entry.isDirectory) { file.mkdirs(); continue }
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        val buffer=ByteArray(8192)
                        while(true) { val n=zip.read(buffer); if(n<0) break; total+=n; require(total<=MAX_TOTAL) { "备份解压体积超过 128 MB" }; output.write(buffer,0,n) }
                    }
                }
            }
            val restored=DataCodec.decode(File(stage,"state.json").readText())
            val prefix=newId()
            val mediaDir=File(filesDir,"media").apply { mkdirs() }
            val jobs=restored.mediaJobs.map { job ->
                if(job.filePath.isBlank()) job else {
                    require(job.filePath.startsWith("media/") && !job.filePath.contains("..") && !job.filePath.contains('\\')) { "媒体路径无效" }
                    val source=File(stage,job.filePath).canonicalFile
                    require(source.path.startsWith(File(stage,"media").canonicalPath+File.separator) && source.isFile) { "备份媒体缺失" }
                    val dest=File(mediaDir,"$prefix-${source.name}"); source.copyTo(dest)
                    job.copy(filePath=dest.absolutePath)
                }
            }
            val workspace=File(stage,"workspace")
            if(workspace.exists()) workspace.walkTopDown().filter { it.isFile }.forEach { source ->
                val target=File(filesDir,"workspace/"+source.relativeTo(workspace).invariantSeparatorsPath).canonicalFile
                require(target.path.startsWith(File(filesDir,"workspace").canonicalPath+File.separator)) { "工作区路径越界" }
                target.parentFile?.mkdirs(); source.copyTo(target,overwrite=true)
            }
            return restored.copy(mediaJobs=jobs)
        } finally {
            check(stage.canonicalPath.startsWith(cacheDir.canonicalPath+File.separator))
            stage.deleteRecursively()
        }
    }
}
