package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Analise posterior dos logs. As perdas sao inferidas por multiplicidade de sensor/temperatura. */
public final class RaftCheck {
    static List<String> lines(Path path) throws IOException {
        if(!Files.exists(path))return List.of();
        List<String> out=new ArrayList<>();
        for(String s:Files.readAllLines(path,StandardCharsets.UTF_8))if(!s.isBlank())out.add(s.replace("\uFEFF",""));
        return out;
    }
    static String value(String line,String key) {
        for(String f:line.split(";"))if(f.startsWith(key+"="))return f.substring(key.length()+1);
        return "";
    }
    private static long number(String line,String key){String v=value(line,key);return v.isEmpty()?-1:Long.parseLong(v);}
    static List<SensorRecord> records(Path path,int n) throws IOException {
        List<SensorRecord> rs=new ArrayList<>();for(String s:lines(path))rs.add(SensorRecord.fromLine(s,n));return rs;
    }
    public static Path leaderPath(Path dir,int k) throws IOException {
        long term=-1,time=-1;int leader=0;
        for(int i=1;i<=Wire.GRUPO;i++)for(String s:lines(dir.resolve("eventos-p"+k+"-n"+i+".txt"))) {
            String[] f=s.split(";");
            if(f.length>2 && f[1].equals("LIDER")){
                long t=number(s,"termo"),at=Long.parseLong(f[0]);
                if(t>term || (t==term && at>time)){term=t;time=at;leader=i;}
            }
        }
        if(leader==0)throw new IOException("Sem lider observado em p"+k);
        return dir.resolve("no-p"+k+"-n"+leader+".txt");
    }
    private static String reading(SensorRecord r){return r.getSensorId()+";"+Double.toString(r.getTemperature());}
    private static final class Election {
        final Set<Integer> candidates=new TreeSet<>(),leaders=new TreeSet<>();
        final List<String> votes=new ArrayList<>();
        long first=Long.MAX_VALUE,elected=Long.MAX_VALUE;
    }
    public static boolean check(int n,Path dir,Path failures) throws IOException {
        StringBuilder report=new StringBuilder("RAFT CHECK\n");
        long unsafe=0,decreases=0,different=0,missingCopies=0,truncated=0,moved=0,totalLost=0,staleBatches=0;
        List<String> sends=lines(dir.resolve("produtor.txt"));
        for(int k=0;k<n;k++) {
            TreeMap<Long,Election> elections=new TreeMap<>();
            List<String> truncations=new ArrayList<>();
            long startup=Long.MAX_VALUE,firstLeader=Long.MAX_VALUE;
            List<List<SensorRecord>> copies=new ArrayList<>();
            List<SensorRecord> canonical;
            try{canonical=records(leaderPath(dir,k),n);}
            catch(IOException e){canonical=List.of();report.append("p").append(k).append(": sem lider final\n");}
            for(int i=1;i<=Wire.GRUPO;i++) {
                Path node=dir.resolve("no-p"+k+"-n"+i+".txt");
                copies.add(records(node,n));
                long previous=-1;
                for(String s:lines(dir.resolve("eventos-p"+k+"-n"+i+".txt"))) {
                    String[] f=s.split(";",-1);long at=Long.parseLong(f[0]);String event=f[1];
                    long t=number(s,"termo");
                    if(event.equals("RECEBIDO") && t>=0 && t<previous)staleBatches++;
                    if(t>=0 && !event.equals("IGNORADO") && !event.equals("RECEBIDO") && !event.equals("RECUPERACAO")) {
                        if(t<previous)decreases++;
                        previous=Math.max(previous,t);
                    }
                    if(value(s,"arranque").equals("sim"))startup=Math.min(startup,at);
                    if(event.equals("TRUNCADO"))truncations.add(s);
                    if(t<1)continue;
                    Election e=elections.computeIfAbsent(t,x->new Election());
                    if(event.equals("CANDIDATO")){e.candidates.add(i);e.first=Math.min(e.first,at);}
                    if(event.equals("LIDER")){e.leaders.add(i);e.elected=Math.min(e.elected,at);firstLeader=Math.min(firstLeader,at);}
                    if(event.equals("VOTO"))e.votes.add("n"+i+"->"+value(s,"candidato")+":"+f[f.length-1]);
                }
                List<String> state=lines(dir.resolve("estado-p"+k+"-n"+i+".txt"));
                if(state.isEmpty()) {missingCopies++;report.append("Estado ausente p").append(k).append(" n").append(i).append('\n');}
                else {
                    long persisted=Long.parseLong(state.get(0).split(";")[0]);
                    if(persisted<previous)decreases++;
                }
            }
            for(Map.Entry<Long,Election> entry:elections.entrySet()) {
                Election e=entry.getValue();if(e.leaders.size()>1)unsafe++;
                report.append("p").append(k).append(" termo=").append(entry.getKey()).append(" candidatos=").append(e.candidates)
                    .append(" votos=").append(e.votes).append(" lideres=").append(e.leaders);
                if(e.leaders.isEmpty() && !e.candidates.isEmpty())report.append(" voto dividido (termo sem lider; pode faltar quorum)");
                if(e.first!=Long.MAX_VALUE && e.elected!=Long.MAX_VALUE)report.append(" eleicao_ms=").append(e.elected-e.first);
                report.append('\n');
            }
            report.append("ARRANQUE;p").append(k).append(";primeiro_lider_ms=")
                .append(firstLeader==Long.MAX_VALUE||startup==Long.MAX_VALUE?-1:firstLeader-startup).append('\n');
            int min=copies.stream().mapToInt(List::size).min().orElse(0),max=copies.stream().mapToInt(List::size).max().orElse(0);
            List<Integer> diffs=new ArrayList<>();
            for(int j=0;j<min;j++) {
                String line=copies.get(0).get(j).toLine();
                if(!line.equals(copies.get(1).get(j).toLine()) || !line.equals(copies.get(2).get(j).toLine()))diffs.add(j+1);
            }
            different+=diffs.size();
            report.append("COINCIDENCIA;p").append(k).append(";divergentes=").append(diffs).append(";tamanhos=")
                .append(Arrays.asList(copies.get(0).size(),copies.get(1).size(),copies.get(2).size()))
                .append(";completa=").append(min==max && diffs.isEmpty()).append('\n');
            if(min!=max)missingCopies++;
            Map<String,Integer> available=new HashMap<>();Set<String> unique=new HashSet<>();
            for(List<SensorRecord> copy:copies)for(SensorRecord r:copy)if(unique.add(r.toLine()))available.merge(reading(r),1,Integer::sum);
            long lost=0,sent=0;
            for(String s:sends) {
                String[] f=s.split(";",-1);if(f.length!=4)throw new IOException("Linha de produtor invalida");
                if(!f[1].equals("p"+k))continue;
                sent++;String key=f[2]+";"+Double.toString(Double.parseDouble(f[3]));int count=available.getOrDefault(key,0);
                if(count==0){lost++;report.append("PERDIDA;").append(s).append('\n');}
                else available.put(key,count-1);
            }
            totalLost+=lost;report.append("LEITURAS;p").append(k).append(";enviadas=").append(sent).append(";perdidas=").append(lost).append('\n');
            for(String s:truncations) {
                long count=number(s,"descartados");truncated+=count;long elsewhere=0;
                String encoded=value(s,"registos");
                if(!encoded.isEmpty())for(String row:new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8).split("\n")) {
                    SensorRecord old=SensorRecord.fromLine(row,n);
                    for(SensorRecord r:canonical)if(r.getSeq()!=old.getSeq() && reading(r).equals(reading(old))){elsewhere++;break;}
                }
                moved+=elsewhere;report.append(s).append(";noutro_seq=").append(elsewhere).append('\n');
            }
            if(failures!=null)for(String s:lines(failures)) {
                String[] f=s.split(";",-1);if(f.length!=3)throw new IOException("Linha falhas invalida");
                if(!f[0].equals("p"+k))continue;
                int failed=Integer.parseInt(f[1].substring(1));long at=Long.parseLong(f[2]),oldTerm=0,recovered=Long.MAX_VALUE;
                for(String h:lines(dir.resolve("hb-p"+k+"-n"+failed+".txt"))) {
                    String[] z=h.split(";");if(Long.parseLong(z[2])<=at)oldTerm=Math.max(oldTerm,Long.parseLong(z[0]));
                }
                for(int i=1;i<=Wire.GRUPO;i++)for(String h:lines(dir.resolve("hb-p"+k+"-n"+i+".txt"))) {
                    String[] z=h.split(";");long time=Long.parseLong(z[2]);
                    if(Long.parseLong(z[0])>oldTerm && time>=at)recovered=Math.min(recovered,time);
                }
                long during=0;
                for(String input:sends){String[] z=input.split(";");long time=Long.parseLong(z[0]);
                    if(z[1].equals("p"+k) && time>=at && time<recovered)during++;}
                report.append("FALHA;").append(s).append(";indisponibilidade_ms=").append(recovered==Long.MAX_VALUE?-1:recovered-at)
                    .append(";leituras_intervalo=").append(during).append('\n');
            }
        }
        // Ler os monitores tambem permite reconhecer observacoes locais, sem as contar como eleicoes.
        for(String id:List.of("produtor","processador")) {
            List<String> monitor=lines(dir.resolve("monitor-"+id+".txt"));
            long notices=monitor.stream().filter(s->s.contains(";NOVO_LIDER;")).count();
            report.append("MONITOR;").append(id).append(";novos_lideres=").append(notices).append('\n');
        }
        report.append("RESUMO;termos_multiplos_lideres=").append(unsafe).append(";termos_decrescentes=").append(decreases)
            .append(";seq_divergentes=").append(different).append(";copias_incompletas=").append(missingCopies)
            .append(";perdidas=").append(totalLost).append(";truncados=").append(truncated).append(";noutro_seq=").append(moved).append('\n');
        report.append("Lotes obsoletos aceites: ").append(staleBatches).append('\n');
        boolean ok=unsafe==0 && decreases==0 && different==0 && missingCopies==0 && staleBatches==0;
        report.append("RESULTADO: ").append(ok?"OK":"FALHOU").append('\n');
        Files.writeString(dir.resolve("raft-check.txt"),report,StandardCharsets.UTF_8);
        System.out.print(report);return ok;
    }
    public static void main(String[] args) {
        try {
            if(args.length<2 || args.length>3)throw new IllegalArgumentException("RaftCheck <N> <pasta> [falhas.txt]");
            if(!check(Integer.parseInt(args[0]),Path.of(args[1]),args.length==3?Path.of(args[2]):null))System.exit(2);
        } catch(Exception e){e.printStackTrace();System.exit(1);}
    }
}
