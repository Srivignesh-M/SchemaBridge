package com.fingress.migration;

import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.fingress.migration.Model.*;

/** Order-independent count and SHA-256 sum. A probabilistic content check, not a proof of equality. */
record DataFingerprint(long count, BigInteger sum) {
    private static final BigInteger MOD=BigInteger.ONE.shiftLeft(256);
    static DataFingerprint empty(){return new DataFingerprint(0,BigInteger.ZERO);}
    DataFingerprint plus(DataFingerprint other){return new DataFingerprint(count+other.count,sum.add(other.sum).mod(MOD));}
    static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
    static String canonical(String kind,String value){return switch(kind){
        case "DECIMAL","INTEGER","BIGINT","SMALLINT" -> new BigDecimal(value).stripTrailingZeros().toPlainString();
        case "TIMESTAMP" -> LocalDateTime.parse(value.replace(' ','T')).toString();
        case "TIMESTAMPTZ" -> OffsetDateTime.parse(value).toInstant().toString();
        case "CHAR" -> value.replaceFirst(" +$","");
        default -> value;
    };}
    static void bytes(MessageDigest digest,InputStream input)throws IOException{try(input){byte[] data=new byte[65536];int n;while((n=input.read(data))!=-1)digest.update(data,0,n);}}
    static void text(MessageDigest digest,Reader input)throws IOException{
        try(input;Writer writer=new OutputStreamWriter(new DigestOutputStream(OutputStream.nullOutputStream(),digest),StandardCharsets.UTF_8)){
            char[] chars=new char[8192];int n;while((n=input.read(chars))!=-1)writer.write(chars,0,n);
        }
    }
    static DataFingerprint staged(Path path,Table table,TransferProgress progress)throws Exception {
        DataFingerprint result=empty();
        try(BufferedReader reader=Files.newBufferedReader(path)){
            String line;while((line=reader.readLine())!=null){progress.check();RowStore.Cell[] cells=RowStore.JSON.readValue(line,RowStore.Cell[].class);MessageDigest row=digest();
                for(int i=0;i<cells.length;i++){
                    RowStore.Cell cell=cells[i];MessageDigest value=digest();
                    if(cell.value()==null&&cell.file()==null)value.update((byte)0);
                    else {value.update((byte)1);if(cell.file()!=null)bytes(value,Files.newInputStream(RowStore.sidecar(path.getParent(),cell.file())));else if(cell.type().equals("BINARY"))value.update(Base64.getDecoder().decode(cell.value()));else value.update(canonical(table.columns().get(i).type().kind(),cell.value()).getBytes(StandardCharsets.UTF_8));}
                    row.update(value.digest());
                }
                result=result.plus(new DataFingerprint(1,new BigInteger(1,row.digest())));
            }
        }return result;
    }
    static DataFingerprint target(Connection connection,Table table,String schema,Dialect dialect,MigrationOptions options,TransferProgress progress)throws Exception{
        DataFingerprint result=empty();
        try(Statement statement=connection.createStatement()){
            progress.statement=statement;statement.setFetchSize(options.fetchSize());statement.setQueryTimeout(options.queryTimeoutSeconds());
            try(ResultSet rs=statement.executeQuery("SELECT "+SqlWriter.names(table.columns().stream().map(Column::name).toList(),dialect)+" FROM "+qualified(schema,table.name(),dialect))){
                while(rs.next()){
                    progress.check();MessageDigest row=digest();
                    for(int i=0;i<table.columns().size();i++){
                        String kind=table.columns().get(i).type().kind();int index=i+1;MessageDigest value=digest();
                        if(kind.equals("TEXT")){Reader input=rs.getCharacterStream(index);if(input==null)value.update((byte)0);else{value.update((byte)1);text(value,input);}}
                        else if(kind.equals("BINARY")){InputStream input=rs.getBinaryStream(index);if(input==null)value.update((byte)0);else{value.update((byte)1);bytes(value,input);}}
                        else if(rs.getObject(index)==null)value.update((byte)0);
                        else {value.update((byte)1);String text=switch(kind){
                            case "DECIMAL","INTEGER","SMALLINT","BIGINT" -> rs.getBigDecimal(index).toPlainString();
                            case "TIMESTAMP" -> rs.getTimestamp(index).toLocalDateTime().toString();
                            case "BOOLEAN" -> Boolean.toString(rs.getBoolean(index));
                            case "DATE" -> rs.getDate(index).toLocalDate().toString();
                            case "TIMESTAMPTZ" -> rs.getObject(index,OffsetDateTime.class).toString();
                            default -> rs.getString(index);
                        };value.update(canonical(kind,text).getBytes(StandardCharsets.UTF_8));}
                        row.update(value.digest());
                    }
                    result=result.plus(new DataFingerprint(1,new BigInteger(1,row.digest())));
                }
            }
        }finally{progress.statement=null;}return result;
    }
}
