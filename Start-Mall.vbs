Option Explicit

Dim files, shell, projectDir, jarPath, javaHome, javaExe, command
Set files = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
projectDir = files.GetParentFolderName(WScript.ScriptFullName)
jarPath = files.BuildPath(projectDir, "target\sports-mall-0.2.9-desktop.jar")

If Not files.FileExists(jarPath) Then
    MsgBox "Build this project with mvnw.cmd package first.", vbExclamation, "Sports Mall"
    WScript.Quit 1
End If

javaHome = shell.Environment("PROCESS")("JAVA_HOME")
If Len(javaHome) > 0 Then
    javaExe = files.BuildPath(javaHome, "bin\javaw.exe")
    If Not files.FileExists(javaExe) Then
        MsgBox "JAVA_HOME does not contain bin\javaw.exe. Check your JDK installation.", vbExclamation, "Sports Mall"
        WScript.Quit 1
    End If
Else
    javaExe = "javaw.exe"
End If

shell.CurrentDirectory = projectDir
Dim processEnv
Set processEnv = shell.Environment("PROCESS")
processEnv.Remove "MALL_DB_PASSWORD"
processEnv.Remove "MALL_MONGO_URI"
command = """" & javaExe & """ -Dfile.encoding=UTF-8 -jar """ & jarPath & """"
On Error Resume Next
shell.Run command, 1, False
If Err.Number <> 0 Then
    MsgBox "Cannot start Java. Install JDK 17+ and check JAVA_HOME.", vbExclamation, "Sports Mall"
    WScript.Quit 1
End If
