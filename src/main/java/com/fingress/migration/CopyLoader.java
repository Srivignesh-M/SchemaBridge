package com.fingress.migration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.HexFormat;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import static com.fingress.migration.Model.*;

/** Optional PostgreSQL loader. Data remains in the caller's table transaction. */
final class CopyLoader {
    static long load(Connection connection,Path file,Table table,String schema,TransferProgress progress)throws Exception {
        CopyIn copy=connection.unwrap(PGConnection.class).getCopyAPI().copyIn("COPY "+qualified(schema,table.name(),Dialect.POSTGRESQL)+" ("+
                SqlWriter.names(table.columns().stream().map(Column::name).toList(),Dialect.POSTGRESQL)+") FROM STDIN WITH (FORMAT csv, NULL '\\N', ENCODING 'UTF8')");
        try{
            OutputStream output=new OutputStream(){
                public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
                public void write(byte[] bytes,int offset,int length)throws IOException{progress.check();try{copy.writeToCopy(bytes,offset,length);progress.bytes+=length;}catch(SQLException e){throw new IOException(e);}}
            };
            long rows=0;
            try(BufferedReader reader=Files.newBufferedReader(file);Writer writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8),65536)){
                String line;while((line=reader.readLine())!=null){progress.check();RowStore.Cell[] cells=RowStore.JSON.readValue(line,RowStore.Cell[].class);
                    for(int i=0;i<cells.length;i++){
                        if(i>0)writer.write(',');RowStore.Cell cell=cells[i];
                        if(cell.value()==null&&cell.file()==null){writer.write("\\N");continue;}
                        writer.write('"');
                        if(cell.file()!=null && cell.type().equals("BINARY")){
                            writer.write("\\x");try(InputStream input=Files.newInputStream(RowStore.sidecar(file.getParent(),cell.file()))){byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1){progress.check();writer.write(HexFormat.of().formatHex(bytes,0,n));}}
                        }else if(cell.file()!=null){try(Reader input=Files.newBufferedReader(RowStore.sidecar(file.getParent(),cell.file()))){char[] chars=new char[8192];int n;while((n=input.read(chars))!=-1){progress.check();writer.write(new String(chars,0,n).replace("\"","\"\""));}}}
                        else if(cell.type().equals("BINARY"))writer.write("\\x"+HexFormat.of().formatHex(java.util.Base64.getDecoder().decode(cell.value())));
                        else writer.write(cell.value().replace("\"","\"\""));
                        writer.write('"');
                    }
                    writer.write('\n');rows++;progress.rowsSent++;
                }
            }
            long copied=copy.endCopy();if(copied!=rows)throw new IOException("COPY row count differs from exported count");return copied;
        }catch(Exception failure){if(copy.isActive())try{copy.cancelCopy();}catch(SQLException ignored){}throw failure;}
    }
}
