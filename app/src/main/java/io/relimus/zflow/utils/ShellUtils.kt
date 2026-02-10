package io.relimus.zflow.utils

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader

/**
 * @author Trinea, sunshine0523
 * @date 2013-5-16
 * http://www.trinea.cn
 * Shell command execution utility.
 * Note: Shizuku-based execution has been removed. Use standard shell commands only.
 */
class ShellUtils private constructor() {

    class CommandResult(
        /** Execution result code */
        var result: Int,
        /** Success output */
        var successMsg: String?,
        /** Error output */
        var errorMsg: String?
    )

    companion object {
        private const val COMMAND_SU = "su"
        private const val COMMAND_SH = "sh"
        private const val COMMAND_EXIT = "exit\n"
        private const val COMMAND_LINE_END = "\n"

        fun execCommand(command: String, isRoot: Boolean): CommandResult {
            return execCommand(arrayOf(command), isRoot)
        }

        private fun execCommand(
            commands: Array<String>?,
            isRoot: Boolean
        ): CommandResult {
            var result = -1
            if (commands == null || commands.isEmpty()) {
                return CommandResult(result, null, null)
            }
            var process: Process? = null
            var successResult: BufferedReader? = null
            var errorResult: BufferedReader? = null
            var successMsg: StringBuilder? = null
            var errorMsg: StringBuilder? = null
            var os: DataOutputStream? = null
            try {
                process = Runtime.getRuntime().exec(
                    if (isRoot) COMMAND_SU else COMMAND_SH
                )
                os = DataOutputStream(process.outputStream)
                for (command in commands) {
                    os.write(command.toByteArray())
                    os.writeBytes(COMMAND_LINE_END)
                    os.flush()
                }
                os.writeBytes(COMMAND_EXIT)
                os.flush()
                result = process.waitFor()
                // get command result
                successMsg = StringBuilder()
                errorMsg = StringBuilder()
                successResult = BufferedReader(
                    InputStreamReader(
                        process.inputStream
                    )
                )
                errorResult = BufferedReader(
                    InputStreamReader(
                        process.errorStream
                    )
                )
                var s: String?
                while (successResult.readLine().also { s = it } != null) {
                    successMsg.append(s)
                }
                while (errorResult.readLine().also { s = it } != null) {
                    errorMsg.append(s)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    os?.close()
                    successResult?.close()
                    errorResult?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                process?.destroy()
            }
            return CommandResult(result, successMsg?.toString(), errorMsg?.toString())
        }
    }
}
