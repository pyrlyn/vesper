package app.vesper.terminal

import app.vesper.platform.HostOs
import app.vesper.platform.Paths
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the command line for a shell with Vesper's integration loaded. The integration
 * reports prompt start (OSC 133;A), command start (133;C), command end with exit code
 * (133;D;n) and the working directory (OSC 7), and hides the shell's own prompt —
 * Vesper draws the prompt itself.
 */
object ShellIntegration {
    private val dir: File by lazy { File(Paths.configDir, "shell-integration").apply { mkdirs() } }

    fun defaultShell(): String = when (HostOs.current) {
        HostOs.Windows -> findOnPath("pwsh.exe") ?: "powershell.exe"
        else -> System.getenv("SHELL")?.takeIf { File(it).canExecute() }
            ?: listOf("/bin/zsh", "/usr/bin/zsh", "/bin/bash", "/usr/bin/bash", "/bin/sh").first { File(it).canExecute() }
    }

    fun build(shell: String = defaultShell(), cwd: String, extraEnv: Map<String, String> = emptyMap()): ShellLaunch {
        val name = File(shell).name.removeSuffix(".exe").lowercase()
        val env = baseEnvironment() + extraEnv
        val login = HostOs.current == HostOs.MacOS
        return when (name) {
            "zsh" -> {
                val zdir = writeZsh()
                ShellLaunch(
                    command = listOfNotNull(shell, if (login) "-l" else null, "-i"),
                    env = env + mapOf(
                        "ZDOTDIR" to zdir.absolutePath,
                        "VESPER_INTEGRATION_DIR" to zdir.absolutePath,
                    ) + (System.getenv("ZDOTDIR")?.let { mapOf("VESPER_ORIG_ZDOTDIR" to it) } ?: emptyMap()),
                    cwd = cwd,
                    shellName = "zsh${version(shell, "--version", Regex("""zsh (\S+)"""))}",
                    integrated = true,
                )
            }
            "bash" -> ShellLaunch(
                command = listOf(shell, "--rcfile", writeBash().absolutePath, "-i"),
                env = env + mapOf("VESPER_BASH_LOGIN" to if (login) "1" else "0"),
                cwd = cwd,
                shellName = "bash${version(shell, "--version", Regex("""version (\d+\.\d+)"""))}",
                integrated = true,
            )
            "fish" -> ShellLaunch(
                command = listOf(shell, "-l", "--init-command", "source '${writeFish().absolutePath}'"),
                env = env,
                cwd = cwd,
                shellName = "fish${version(shell, "--version", Regex("""version (\S+)"""))}",
                integrated = true,
            )
            "pwsh", "powershell" -> ShellLaunch(
                command = listOf(shell, "-NoLogo", "-NoExit", "-Command", ". '${writePowerShell().absolutePath}'"),
                env = env,
                cwd = cwd,
                shellName = name,
                integrated = true,
            )
            else -> ShellLaunch(listOf(shell), env, cwd, name, integrated = false)
        }
    }

    private fun baseEnvironment(): Map<String, String> {
        val env = HashMap(System.getenv())
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["TERM_PROGRAM"] = "Vesper"
        env["TERM_PROGRAM_VERSION"] = Paths.appVersion
        env.remove("TERMINAL_EMULATOR")
        if (HostOs.current != HostOs.Windows) {
            if (env["LANG"].isNullOrBlank()) env["LANG"] = "en_US.UTF-8"
            LoginEnvironment.path?.let { env["PATH"] = it }
        }
        return env
    }

    private fun version(shell: String, flag: String, regex: Regex): String = runCatching {
        val p = ProcessBuilder(shell, flag).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(2, TimeUnit.SECONDS)
        regex.find(out)?.groupValues?.get(1)?.let { " $it" } ?: ""
    }.getOrDefault("")

    private fun findOnPath(exe: String): String? = System.getenv("PATH")?.split(File.pathSeparator)
        ?.map { File(it, exe) }?.firstOrNull { it.isFile }?.absolutePath

    private fun write(name: String, content: String): File = File(dir, name).apply {
        parentFile.mkdirs()
        if (!exists() || readText() != content) writeText(content)
    }

    private fun writeZsh(): File {
        val zdir = File(dir, "zsh").apply { mkdirs() }
        write("zsh/.zshenv", ZSHENV)
        write("zsh/.zprofile", ZPROFILE)
        write("zsh/.zshrc", ZSHRC)
        write("zsh/.zlogin", ZLOGIN)
        return zdir
    }

    private fun writeBash() = write("vesper.bash", BASHRC)
    private fun writeFish() = write("vesper.fish", FISH)
    private fun writePowerShell() = write("vesper.ps1", POWERSHELL)

    private val ZSHENV = """
        # Vesper shell integration: load the user's startup files, then ours.
        if [[ -n "${'$'}VESPER_ORIG_ZDOTDIR" ]]; then ZDOTDIR="${'$'}VESPER_ORIG_ZDOTDIR"; else ZDOTDIR="${'$'}HOME"; fi
        [[ -f "${'$'}ZDOTDIR/.zshenv" ]] && builtin source "${'$'}ZDOTDIR/.zshenv"
        VESPER_USER_ZDOTDIR="${'$'}ZDOTDIR"
        ZDOTDIR="${'$'}VESPER_INTEGRATION_DIR"
    """.trimIndent() + "\n"

    private val ZPROFILE = """
        ZDOTDIR="${'$'}VESPER_USER_ZDOTDIR"
        [[ -f "${'$'}ZDOTDIR/.zprofile" ]] && builtin source "${'$'}ZDOTDIR/.zprofile"
        VESPER_USER_ZDOTDIR="${'$'}ZDOTDIR"
        ZDOTDIR="${'$'}VESPER_INTEGRATION_DIR"
    """.trimIndent() + "\n"

    private val ZSHRC = """
        ZDOTDIR="${'$'}VESPER_USER_ZDOTDIR"
        [[ -f "${'$'}ZDOTDIR/.zshrc" ]] && builtin source "${'$'}ZDOTDIR/.zshrc"
        VESPER_USER_ZDOTDIR="${'$'}ZDOTDIR"
        ZDOTDIR="${'$'}VESPER_INTEGRATION_DIR"

        if [[ -o interactive && -z "${'$'}__vesper_loaded" ]]; then
          typeset -g __vesper_loaded=1 __vesper_running=0
          __vesper_osc() { builtin printf '\e]%s\a' "${'$'}1" }
          # Runs first: capture the exit status before other hooks change ${'$'}?.
          __vesper_precmd_first() {
            local ec=${'$'}?
            if (( __vesper_running )); then __vesper_osc "133;D;${'$'}ec"; fi
            __vesper_running=0
          }
          # Runs last: report the cwd and replace whatever prompt a theme set.
          __vesper_precmd_last() {
            __vesper_osc "7;file://${'$'}{HOST}${'$'}{PWD// /%20}"
            __vesper_osc "133;A"
            PS1=${'$'}'%{\e]133;B\a%}'
            RPS1=''
            PS2=''
          }
          # Reports the command line as typed (percent-escaped), so blocks are named right even
          # for type-ahead or when a theme redraws the prompt (transient prompts).
          __vesper_preexec() {
            __vesper_running=1
            local c=${'$'}{1//\%/%25}
            c=${'$'}{c//${'$'}'\n'/%0A}; c=${'$'}{c//${'$'}'\a'/%07}; c=${'$'}{c//${'$'}'\e'/%1B}
            __vesper_osc "6973;cmd;${'$'}c"
            __vesper_osc "133;C"
          }
          autoload -Uz add-zsh-hook
          add-zsh-hook preexec __vesper_preexec
          precmd_functions=(__vesper_precmd_first ${'$'}{precmd_functions:#__vesper_precmd_*} __vesper_precmd_last)
          unsetopt PROMPT_SP 2>/dev/null
        fi
        # Non-login shells never read .zlogin: hand ZDOTDIR back to the user now.
        [[ -o login ]] || ZDOTDIR="${'$'}VESPER_USER_ZDOTDIR"
    """.trimIndent() + "\n"

    private val ZLOGIN = """
        ZDOTDIR="${'$'}VESPER_USER_ZDOTDIR"
        [[ -f "${'$'}ZDOTDIR/.zlogin" ]] && builtin source "${'$'}ZDOTDIR/.zlogin"
        # Keep precmd ordering if .zlogin added hooks.
        if (( ${'$'}+functions[__vesper_precmd_last] )); then
          precmd_functions=(__vesper_precmd_first ${'$'}{precmd_functions:#__vesper_precmd_*} __vesper_precmd_last)
        fi
    """.trimIndent() + "\n"

    private val BASHRC = """
        # Vesper shell integration for bash.
        if [[ "${'$'}VESPER_BASH_LOGIN" == 1 ]]; then
          [[ -f /etc/profile ]] && . /etc/profile
          if [[ -f ~/.bash_profile ]]; then . ~/.bash_profile
          elif [[ -f ~/.bash_login ]]; then . ~/.bash_login
          elif [[ -f ~/.profile ]]; then . ~/.profile; fi
        else
          [[ -f ~/.bashrc ]] && . ~/.bashrc
        fi
        __vesper_running=0
        __vesper_in_prompt=0
        __vesper_prompt_start() {
          local ec=${'$'}?
          __vesper_in_prompt=1
          if [[ ${'$'}__vesper_running == 1 ]]; then builtin printf '\e]133;D;%s\a' "${'$'}ec"; fi
          __vesper_running=0
          return ${'$'}ec
        }
        __vesper_prompt_end() {
          builtin printf '\e]7;file://%s%s\a' "${'$'}HOSTNAME" "${'$'}{PWD// /%20}"
          builtin printf '\e]133;A\a'
          PS1='\[\e]133;B\a\]'
          PS2=''
          __vesper_in_prompt=0
        }
        __vesper_debug() {
          [[ ${'$'}__vesper_in_prompt == 1 || ${'$'}__vesper_running == 1 || -n "${'$'}COMP_LINE" ]] && return
          [[ "${'$'}BASH_COMMAND" == __vesper_prompt_start* ]] && return
          __vesper_running=1
          builtin printf '\e]133;C\a'
        }
        PROMPT_COMMAND="__vesper_prompt_start${'$'}{PROMPT_COMMAND:+; ${'$'}PROMPT_COMMAND}; __vesper_prompt_end"
        trap '__vesper_debug' DEBUG
    """.trimIndent() + "\n"

    private val FISH = """
        # Vesper shell integration for fish.
        set -g fish_greeting
        function __vesper_preexec --on-event fish_preexec
            printf '\e]133;C\a'
        end
        function __vesper_postexec --on-event fish_postexec
            printf '\e]133;D;%s\a' ${'$'}status
        end
        function fish_prompt
            printf '\e]7;file://%s%s\a' (hostname) (string replace -a ' ' '%20' -- ${'$'}PWD)
            printf '\e]133;A\a\e]133;B\a'
        end
        function fish_right_prompt
        end
        function fish_mode_prompt
        end
    """.trimIndent() + "\n"

    private val POWERSHELL = """
        # Vesper shell integration for PowerShell.
        ${'$'}global:__VesperLastId = -1
        function global:prompt {
            ${'$'}ok = ${'$'}global:?
            ${'$'}code = ${'$'}global:LASTEXITCODE
            ${'$'}e = [char]27; ${'$'}b = [char]7
            ${'$'}out = ""
            ${'$'}h = Get-History -Count 1
            if (${'$'}h -and ${'$'}h.Id -ne ${'$'}global:__VesperLastId) {
                ${'$'}global:__VesperLastId = ${'$'}h.Id
                if (${'$'}ok) { ${'$'}ec = 0 } elseif (${'$'}code) { ${'$'}ec = ${'$'}code } else { ${'$'}ec = 1 }
                ${'$'}out += "${'$'}e]133;D;${'$'}ec${'$'}b"
            }
            ${'$'}p = (${'$'}executionContext.SessionState.Path.CurrentLocation.ProviderPath -replace '\\','/') -replace ' ','%20'
            if (-not ${'$'}p.StartsWith('/')) { ${'$'}p = '/' + ${'$'}p }
            ${'$'}out += "${'$'}e]7;file://${'$'}env:COMPUTERNAME${'$'}p${'$'}b${'$'}e]133;A${'$'}b${'$'}e]133;B${'$'}b"
            ${'$'}global:LASTEXITCODE = ${'$'}code
            return ${'$'}out
        }
        if (Get-Module -ListAvailable PSReadLine) {
            Set-PSReadLineKeyHandler -Chord Enter -ScriptBlock {
                [Microsoft.PowerShell.PSConsoleReadLine]::AcceptLine()
                [Console]::Write("${'$'}([char]27)]133;C${'$'}([char]7)")
            }
        }
    """.trimIndent() + "\n"
}

/** PATH from a login shell, so apps started from Finder/Explorer still find user tools. */
object LoginEnvironment {
    val path: String? by lazy {
        if (HostOs.current == HostOs.Windows) return@lazy null
        val shell = System.getenv("SHELL") ?: "/bin/zsh"
        runCatching {
            val p = ProcessBuilder(shell, "-lic", "printf '__VESPER_PATH__%s' \"\$PATH\"")
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            val done = p.waitFor(4, TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                return@runCatching null
            }
            p.inputStream.bufferedReader().readText().substringAfter("__VESPER_PATH__", "").trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
