package cn.zaozhi.tool;

import cn.zaozhi.*;
import cn.zaozhi.agent.AgentFailure;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ShellExecutorTest {
    @TempDir Path dir;
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    long deadline() { return System.nanoTime()+TimeUnit.SECONDS.toNanos(15); }
    ShellExecutor executor() { return new ShellExecutor(new Settings(Map.of())); }
    Workspace workspace() throws Exception { return new Workspace(dir.resolve("work")); }

    @Test void pwdAndLsComeFromRealSupervisedProcessesWithUtf8AndRedactedPaths() throws Exception {
        Workspace work=workspace();work.systemWrite("新闻.txt","fixture");List<ObjectNode> events=new ArrayList<>();
        var pwd=executor().execute("pwd",work,deadline(),()->{},events::add);
        var ls=executor().execute("ls",work,deadline(),()->{},events::add);
        assertEquals("/workspace",pwd.path("stdout").asText().strip());
        assertTrue(ls.path("stdout").asText().contains("新闻.txt"));
        assertEquals(0,pwd.path("exit_code").asInt());assertTrue(pwd.path("pid").asLong()>0);
        assertTrue(pwd.path("process_reaped").asBoolean());assertFalse(pwd.path("truncated").asBoolean());
        assertEquals(WINDOWS?"windows_job_object":"linux_process_group",pwd.path("supervisor").asText());
        assertEquals(4,events.size());assertFalse(Json.stringify(events).contains(dir.toString()));
        assertFalse(ProcessHandle.of(pwd.path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void deniesArgumentsPipesExpansionNewlinesAndWhitespaceBeforeStartingAnyProcess() throws Exception {
        Workspace work=workspace();List<ObjectNode> events=new ArrayList<>();
        for(String command:List.of("ls -a","pwd; ls","pwd && ls","pwd | ls","pwd\nls","$env:PATH","pwd > x"," pwd","ls ","$(pwd)","pwd\u0000")) {
            var error=assertThrows(ShellExecutor.ShellFailure.class,()->executor().execute(command,work,deadline(),()->{},events::add));
            assertEquals("COMMAND_DENIED",error.code);
        }
        assertTrue(events.isEmpty());assertFalse(Files.exists(dir.resolve("work/.shell-tmp")));
    }

    @Test void bothStreamsAreConsumedAndNonzeroExitIsNotReportedAsSuccess() throws Exception {
        String script=WINDOWS?"[Console]::Out.Write('out'); [Console]::Error.Write('err'); exit 7":"printf out; printf err >&2; exit 7";
        var error=assertThrows(ShellExecutor.ShellFailure.class,()->executor().executeScript("fixture",script,workspace(),deadline(),()->{},e->{}));
        assertEquals("SHELL_EXIT_NONZERO",error.code);assertEquals(7,error.data.path("exit_code").asInt());
        assertEquals("out",error.data.path("stdout").asText());assertEquals("err",error.data.path("stderr").asText());
    }

    @Test void combinedOutputLimitKillsTheProcessAndPreservesBoundedEvidence() throws Exception {
        String script=WINDOWS?"[Console]::Out.Write('o'*12000); [Console]::Error.Write('e'*12000); Start-Sleep -Seconds 20":"printf '%12000s' ''; printf '%12000s' '' >&2; sleep 20";
        var error=assertThrows(ShellExecutor.ShellFailure.class,()->executor().executeScript("fixture",script,workspace(),deadline(),()->{},e->{}));
        assertEquals("SHELL_OUTPUT_LIMIT",error.code);assertTrue(error.data.path("truncated").asBoolean());
        assertEquals(16384,error.data.path("output_bytes").asInt());assertTrue(error.data.path("process_reaped").asBoolean());
        assertTrue(error.data.path("stdout").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+error.data.path("stderr").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=16384);
    }

    @Test void timeoutKillsParentAndDescendant() throws Exception {
        Workspace work=workspace();AtomicLong parent=new AtomicLong();
        var shortExecutor=new ShellExecutor(new Settings(Map.of("SHELL_TIMEOUT_MS","3500")));
        var error=assertThrows(ShellExecutor.ShellFailure.class,()->shortExecutor.executeScript("fixture",childScript(true),work,deadline(),()->{},e->{if(e.path("phase").asText().equals("started"))parent.set(e.path("pid").asLong());}));
        assertEquals("SHELL_TIMEOUT",error.code);assertDead(parent.get());assertDead(childPid(work));
    }

    @Test void cancellationKillsParentAndDescendantAndKeepsBusinessReason() throws Exception {
        Workspace work=workspace();AtomicLong parent=new AtomicLong();
        var error=assertThrows(AgentFailure.class,()->executor().executeScript("fixture",childScript(true),work,deadline(),()->{if(Files.exists(dir.resolve("work/child.pid")))throw new AgentFailure("USER_CANCELLED","cancel fixture");},e->{if(e.path("phase").asText().equals("started"))parent.set(e.path("pid").asLong());}));
        assertEquals("USER_CANCELLED",error.code);assertDead(parent.get());assertDead(childPid(work));
    }

    @Test void normalParentExitAlsoReapsItsRemainingDescendant() throws Exception {
        Workspace work=workspace();executor().executeScript("fixture",childScript(false),work,deadline(),()->{},e->{});assertDead(childPid(work));
    }

    @Test void threadInterruptionReapsShellAndRestoresInterruptFlag() throws Exception {
        AtomicLong pid=new AtomicLong();Workspace work=workspace();var pool=Executors.newSingleThreadExecutor();
        var finished=new CountDownLatch(1);var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        try{
            var future=pool.submit(()->{try{executor().executeScript("fixture",WINDOWS?"Start-Sleep -Seconds 30":"sleep 30",work,deadline(),()->{},e->{if(e.path("phase").asText().equals("started"))pid.set(e.path("pid").asLong());});}catch(Exception e){interrupted.set(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();finished.countDown();}});
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(pid.get()==0&&System.nanoTime()<until)Thread.sleep(20);
            assertTrue(pid.get()>0);future.cancel(true);assertTrue(finished.await(5,TimeUnit.SECONDS));assertTrue(interrupted.get());assertDead(pid.get());
        }finally{pool.shutdownNow();}
    }

    @Test void inheritedSecretsAndStartupEnvironmentAreAbsentInTheChild() throws Exception {
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin",WINDOWS?"java.exe":"java").toString();
        var builder=new ProcessBuilder(javaExecutable,"-cp",System.getProperty("java.class.path"),EnvironmentFixture.class.getName(),dir.resolve("env").toString()).redirectErrorStream(true);
        builder.environment().put("DEEPSEEK_API_KEY","test-inherited-secret");builder.environment().put("SMTP_PASSWORD","test-mail-secret");builder.environment().put("BASH_ENV","test-startup");
        Process parent=builder.start();try{assertTrue(parent.waitFor(15,TimeUnit.SECONDS));assertEquals(0,parent.exitValue(),new String(parent.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}finally{if(parent.isAlive()){parent.descendants().forEach(ProcessHandle::destroyForcibly);parent.destroyForcibly();}}
    }

    private static String childScript(boolean wait) {
        if(!WINDOWS)return "sleep 60 & child=$!; printf '%s' \"$child\" > child.pid; "+(wait?"sleep 60":"exit 0");
        String shell=Path.of(System.getenv().getOrDefault("SystemRoot","C:\\Windows"),"System32","WindowsPowerShell","v1.0","powershell.exe").toString().replace("'","''");
        return "$child=Start-Process -FilePath '"+shell+"' -ArgumentList '-NoLogo','-NoProfile','-NonInteractive','-WindowStyle','Hidden','-Command','Start-Sleep -Seconds 60' -WindowStyle Hidden -PassThru; [IO.File]::WriteAllText((Join-Path (Get-Location) 'child.pid'),$child.Id.ToString()); "+(wait?"Start-Sleep -Seconds 60":"exit 0");
    }
    private long childPid(Workspace work)throws Exception{assertTrue(Files.exists(work.resolve("child.pid")));return Long.parseLong(Files.readString(work.resolve("child.pid")).strip());}
    private void assertDead(long pid)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)&&System.nanoTime()<end)Thread.sleep(20);assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"process remains: "+pid);}

    public static class EnvironmentFixture {
        public static void main(String[] args)throws Exception {
            if(!"test-inherited-secret".equals(System.getenv("DEEPSEEK_API_KEY")))throw new IllegalStateException("parent fixture missing");
            String script=WINDOWS?"[Environment]::GetEnvironmentVariables().Keys | Sort-Object":"env | cut -d= -f1";
            var result=new ShellExecutor(new Settings(Map.of())).executeScript("fixture",script,new Workspace(Path.of(args[0])),System.nanoTime()+TimeUnit.SECONDS.toNanos(10),()->{},e->{});
            String output=result.path("stdout").asText();for(String forbidden:List.of("DEEPSEEK_API_KEY","SMTP_PASSWORD","BASH_ENV","USERPROFILE"))if(output.contains(forbidden))throw new IllegalStateException("inherited environment leaked");
        }
    }
}
