package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static com.fingress.migration.Model.*;

/** Typed rows, with streamed LOB sidecars. No SQL parsing in the database load path. */
final class RowStore {
    static final ObjectMapper JSON = new ObjectMapper();
    record Cell(String type, String value, String file) {}
    @FunctionalInterface interface DiskSpaceProbe { long usableSpace(Path path) throws IOException; }
    static final DiskSpaceProbe SYSTEM_DISK_SPACE = path -> Files.getFileStore(path).getUsableSpace();
    static Cell[] read(ResultSet rs, Table source, Dialect target, Path directory, MigrationOptions options, TransferProgress progress, DiskSpaceProbe diskSpace) throws Exception {
        Cell[] row = new Cell[source.columns().size()];
        for (int i=0;i<row.length;i++) {
            String kind = source.columns().get(i).type().kind(); int index=i+1;
            if (kind.equals("BINARY") || kind.equals("TEXT")) {
                String file = "lob-"+UUID.randomUUID(); Path path=directory.resolve(file);
                if (kind.equals("BINARY")) {
                    InputStream input = rs.getBinaryStream(index);
                    if(input==null) { row[i]=new Cell(kind,null,null); continue; }
                    try(input){
                        byte[] prefix=input.readNBytes(8193);
                        if(prefix.length<=8192){row[i]=new Cell(kind,Base64.getEncoder().encodeToString(prefix),null);continue;}
                        try(OutputStream out=Files.newOutputStream(path)){out.write(prefix);copy(input,out,path,options,progress,diskSpace);}
                    }
                } else {
                    Reader input=rs.getCharacterStream(index);
                    if(input==null) { row[i]=new Cell(kind,null,null); continue; }
                    try(input){
                        char[] prefix=new char[8193];int length=0,n;
                        while(length<prefix.length && (n=input.read(prefix,length,prefix.length-length))!=-1)length+=n;
                        String initial=new String(prefix,0,length);
                        if(initial.indexOf('\0')>=0)throw new IllegalArgumentException("NUL text is not portable between databases");
                        if(target==Dialect.ORACLE&&initial.isEmpty())throw new IllegalArgumentException("Empty text would become NULL in Oracle");
                        if(length<=8192){row[i]=new Cell(kind,initial,null);continue;}
                        try(Writer out=Files.newBufferedWriter(path)){
                        out.write(initial);char[] buffer=new char[8192]; long size=length;
                        while((n=input.read(buffer))!=-1) {
                            progress.check();
                            for(int x=0;x<n;x++)if(buffer[x]=='\0')throw new IllegalArgumentException("NUL text is not portable between databases");
                            out.write(buffer,0,n);size+=n;
                            if(size>options.maxTableBytes())throw new IllegalArgumentException("LOB exceeds configured size limit");
                            if(size%65536<8192){out.flush();checkDisk(path,options,diskSpace);}
                        }
                        }
                    }
                    if(target==Dialect.ORACLE && Files.size(path)==0)throw new IllegalArgumentException("Empty text would become NULL in Oracle; choose an explicit mapping");
                }
                row[i]=new Cell(kind,null,file); continue;
            }
            Object object=rs.getObject(index);
            if(object==null){row[i]=new Cell(kind,null,null);continue;}
            String value=switch(kind) {
                case "DECIMAL","INTEGER","SMALLINT","BIGINT" -> rs.getBigDecimal(index).toPlainString();
                case "BOOLEAN" -> Boolean.toString(rs.getBoolean(index));
                case "DATE" -> rs.getDate(index).toLocalDate().toString();
                case "TIMESTAMP" -> rs.getTimestamp(index).toLocalDateTime().toString();
                case "TIMESTAMPTZ" -> rs.getObject(index,OffsetDateTime.class).toString();
                default -> rs.getString(index);
            };
            if(value.indexOf('\0')>=0)throw new IllegalArgumentException("NUL text is not portable between databases");
            if(target==Dialect.ORACLE && value.isEmpty())throw new IllegalArgumentException("Empty string would become NULL in Oracle; choose an explicit mapping");
            row[i]=new Cell(kind,value,null);
        }
        return row;
    }
    private static void copy(InputStream input,OutputStream out,Path path,MigrationOptions options,TransferProgress progress,DiskSpaceProbe diskSpace)throws IOException {
        byte[] bytes=new byte[65536];int n;long total=0;
        while((n=input.read(bytes))!=-1){progress.check();total+=n;if(total>options.maxTableBytes())throw new IllegalArgumentException("LOB exceeds configured size limit");out.write(bytes,0,n);checkDisk(path,options,diskSpace);}
    }
    static void checkDisk(Path path,MigrationOptions options)throws IOException {
        checkDisk(path,options,SYSTEM_DISK_SPACE);
    }
    static void checkDisk(Path path,MigrationOptions options,DiskSpaceProbe diskSpace)throws IOException {
        if(diskSpace.usableSpace(path)<options.diskReserveBytes())throw new IOException("Disk reserve reached");
    }
    static Path sidecar(Path directory,String name) {
        if(name==null || !name.matches("lob-[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid LOB reference");
        Path path=directory.resolve(name);
        if(Files.isSymbolicLink(path))throw new IllegalArgumentException("Invalid LOB reference");return path;
    }
    static void bind(PreparedStatement statement,Cell[] row,Table table,Dialect dialect,Path directory,List<Closeable> streams)throws Exception {
        if(row.length!=table.columns().size())throw new IOException("Row shape differs from table definition");
        for(int i=0;i<row.length;i++) {
            Cell cell=row[i];int index=i+1;String kind=table.columns().get(i).type().kind();
            if(!kind.equals(cell.type()) && !(Set.of("DECIMAL","INTEGER","BIGINT","SMALLINT").contains(kind)&&Set.of("DECIMAL","INTEGER","BIGINT","SMALLINT").contains(cell.type())))throw new IOException("Row type differs from table definition");
            if(cell.value()==null && cell.file()==null){statement.setNull(index,jdbcType(kind,dialect));continue;}
            if(cell.file()!=null){
                Path file=sidecar(directory,cell.file());
                if(kind.equals("BINARY")){InputStream input=Files.newInputStream(file);streams.add(input);statement.setBinaryStream(index,input,Files.size(file));}
                else if(kind.equals("TEXT")){Reader reader=Files.newBufferedReader(file);streams.add(reader);statement.setCharacterStream(index,reader);}
                else throw new IOException("Unexpected LOB type");continue;
            }
            switch(kind){
                case "DECIMAL","INTEGER","SMALLINT","BIGINT" -> statement.setBigDecimal(index,new BigDecimal(cell.value()));
                case "BINARY" -> statement.setBytes(index,Base64.getDecoder().decode(cell.value()));
                case "BOOLEAN" -> statement.setBoolean(index,Boolean.parseBoolean(cell.value()));
                case "DATE" -> statement.setDate(index,java.sql.Date.valueOf(cell.value()));
                case "TIMESTAMP" -> statement.setTimestamp(index,java.sql.Timestamp.valueOf(LocalDateTime.parse(cell.value())));
                case "TIMESTAMPTZ" -> statement.setObject(index,OffsetDateTime.parse(cell.value()));
                default -> statement.setString(index,cell.value());
            }
        }
    }
    static int jdbcType(String kind,Dialect dialect){return switch(kind){
        case "DECIMAL","INTEGER","SMALLINT","BIGINT" -> Types.NUMERIC;
        case "BOOLEAN" -> Types.BOOLEAN;
        case "DATE" -> Types.DATE; case "TIMESTAMP" -> Types.TIMESTAMP;case "TIMESTAMPTZ" -> Types.TIMESTAMP_WITH_TIMEZONE;
        case "TEXT" -> dialect==Dialect.ORACLE?Types.CLOB:Types.VARCHAR;case "BINARY" -> dialect==Dialect.ORACLE?Types.BLOB:Types.VARBINARY;default -> Types.VARCHAR;
    };}
    static String insertSql(Table table,String schema,Dialect dialect){return "INSERT INTO "+qualified(schema,table.name(),dialect)+" ("+SqlWriter.names(table.columns().stream().map(Column::name).toList(),dialect)+")"+(dialect==Dialect.POSTGRESQL && table.columns().stream().anyMatch(Column::always)?" OVERRIDING SYSTEM VALUE":"")+" VALUES ("+String.join(",",Collections.nCopies(table.columns().size(),"?"))+")";}
    static String hash(Path file)throws IOException {
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static void sqlRows(Path file,Table table,String schema,Dialect dialect,OutputStream out)throws IOException {
        try(BufferedReader reader=Files.newBufferedReader(file)){
            String line;while((line=reader.readLine())!=null){
                Cell[] row=JSON.readValue(line,Cell[].class);
                String prefix="INSERT INTO "+qualified(schema,table.name(),dialect)+" ("+SqlWriter.names(table.columns().stream().map(Column::name).toList(),dialect)+")"+(dialect==Dialect.POSTGRESQL&&table.columns().stream().anyMatch(Column::always)?" OVERRIDING SYSTEM VALUE":"")+" VALUES (";
                write(out,prefix);
                for(int i=0;i<row.length;i++){if(i>0)write(out,",");writeLiteral(out,row[i],file.getParent(),dialect);}
                write(out,");\n");
            }
        }
    }
    private static void writeLiteral(OutputStream out,Cell cell,Path directory,Dialect dialect)throws IOException {
        if(cell.value()==null && cell.file()==null){write(out,"NULL");return;}
        if(cell.file()!=null){
            Path file=sidecar(directory,cell.file());
            if(cell.type().equals("BINARY")){
                if(dialect==Dialect.ORACLE && Files.size(file)>1000)throw new IOException("Large Oracle BLOB exports require database transfer; SQL literal export is unavailable");
                write(out,dialect==Dialect.ORACLE?"HEXTORAW('":"decode('");
                try(InputStream input=Files.newInputStream(file)){byte[] bytes=new byte[4096];int n;while((n=input.read(bytes))!=-1)write(out,HexFormat.of().formatHex(bytes,0,n));}
                write(out,dialect==Dialect.ORACLE?"')":"','hex')");
            }else{
                if(dialect==Dialect.ORACLE && Files.size(file)>2000)throw new IOException("Large Oracle CLOB exports require database transfer; SQL literal export is unavailable");
                write(out,"'");Writer writer=new OutputStreamWriter(out,StandardCharsets.UTF_8);
                try(Reader reader=Files.newBufferedReader(file)){char[] chars=new char[4096];int n;while((n=reader.read(chars))!=-1)writer.write(new String(chars,0,n).replace("'","''"));}writer.flush();write(out,"'");
            }return;
        }
        String value=cell.value();
        switch(cell.type()){
            case "DECIMAL","INTEGER","SMALLINT","BIGINT" -> write(out,new BigDecimal(value).toPlainString());
            case "BINARY" -> write(out,(dialect==Dialect.ORACLE?"HEXTORAW('":"decode('")+HexFormat.of().formatHex(Base64.getDecoder().decode(value))+(dialect==Dialect.ORACLE?"')":"','hex')"));
            case "BOOLEAN" -> write(out,Boolean.parseBoolean(value)?"TRUE":"FALSE");
            case "DATE" -> write(out,"DATE '"+value+"'");
            case "TIMESTAMP" -> write(out,"TIMESTAMP '"+value.replace('T',' ')+"'");
            case "TIMESTAMPTZ" -> write(out,dialect==Dialect.POSTGRESQL?"TIMESTAMP WITH TIME ZONE '"+value.replace('T',' ')+"'":"TO_TIMESTAMP_TZ('"+OffsetDateTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSSxxx"))+"','YYYY-MM-DD HH24:MI:SS.FF9TZH:TZM')");
            default -> write(out,"'"+value.replace("'","''")+"'");
        }
    }
    private static void write(OutputStream out,String value)throws IOException{out.write(value.getBytes(StandardCharsets.UTF_8));}
}
