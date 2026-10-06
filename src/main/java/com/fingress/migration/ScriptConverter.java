package com.fingress.migration;

import java.util.*;
import java.util.stream.Collectors;
import static com.fingress.migration.Model.*;
import static com.fingress.migration.SqlParser.Token;

/** Ordered script translation. It never reorders mutations into table-cloning phases. */
public final class ScriptConverter {
    public record Result(List<Table> tables, List<StatementReport> statements, List<String> issues, boolean ordered) {}
    private final Dialect source,target; private final String schema;
    private final Map<String,Table> current=new LinkedHashMap<>(), history=new LinkedHashMap<>();
    private final Map<String,String> indexes=new HashMap<>();
    private final Set<String> failedTables=new HashSet<>();
    private String sourceSchema;
    private List<String> messages; private boolean review; private Cursor c;
    public ScriptConverter(Dialect source,Dialect target,String schema){this.source=source;this.target=target;this.schema=schema;}
    public Result convert(String script){
        List<StatementReport> reports=new ArrayList<>();List<String> issues=new ArrayList<>();boolean ordered=false;
        List<List<Token>> statements;
        try{statements=split(SqlParser.lex(script),";");}catch(IllegalArgumentException e){return new Result(List.of(),List.of(),List.of(e.getMessage()),true);}
        int number=0;
        for(List<Token> tokens:statements){
            if(tokens.isEmpty())continue; number++;c=new Cursor(tokens);messages=new ArrayList<>();review=false;
            boolean simple=isSimple(tokens); ordered|=!simple;
            Map<String,Table> snapshot=new LinkedHashMap<>(current), priorHistory=new LinkedHashMap<>(history);Map<String,String> priorIndexes=new HashMap<>(indexes);
            String kind=tokens.getFirst().text().toUpperCase(Locale.ROOT);
            try{
                String sql=statement(tokens);c.end();reports.add(new StatementReport(number,kind,review?"REVIEW":"CONVERTED",sql,List.copyOf(new LinkedHashSet<>(messages))));
            }catch(IllegalArgumentException e){
                current.clear();current.putAll(snapshot);history.clear();history.putAll(priorHistory);indexes.clear();indexes.putAll(priorIndexes);
                if(tokens.size()>2&&tokens.get(0).is("CREATE")&&tokens.get(1).is("TABLE"))failedTables.add(new Name(tokens.get(2).text(),tokens.get(2).kind()=='i').in(target));
                String message=e.getMessage();reports.add(new StatementReport(number,kind,e instanceof Unsupported?"UNSUPPORTED":"ERROR","",List.of(message)));
                issues.add("Statement "+number+": "+message);
            }
        }
        return new Result(List.copyOf(history.values()),List.copyOf(reports),List.copyOf(issues),ordered);
    }
    static boolean isSimple(List<Token> tokens){return tokens.getFirst().is("COMMIT") || tokens.size()>1&&(tokens.get(0).is("CREATE")&&tokens.get(1).is("TABLE")||tokens.get(0).is("INSERT")&&tokens.get(1).is("INTO"));}
    private String statement(List<Token> tokens){
        if(c.take("CREATE")){
            if(c.take("TABLE"))return createTable(tokens);
            boolean unique=c.take("UNIQUE");
            if(c.take("INDEX"))return createIndex(unique);
            if(!unique&&c.take("SEQUENCE"))return sequence();
            throw unsupported("CREATE object type is not supported by the ordered converter");
        }
        if(c.take("ALTER")){c.need("TABLE");return alter();}
        if(c.take("INSERT")){
            if(c.take("ALL"))return multiInsert(false);
            if(c.take("FIRST"))return multiInsert(true);
            c.p=0;return insert(tokens);
        }
        if(c.take("UPDATE"))return update();
        if(c.take("DELETE"))return delete();
        if(c.take("MERGE"))return merge();
        if(c.take("DROP"))return drop();
        if(c.take("RENAME")){Name old=c.object();c.need("TO");return rename(old,c.name());}
        if(c.take("TRUNCATE")){c.need("TABLE");Name name=c.object();require(name);review("TRUNCATE removes all rows; review before running the ordered script.");return "TRUNCATE TABLE "+q(name);}
        if(c.take("COMMENT"))return comment();
        if(c.take("GRANT"))return privilege(false);
        if(c.take("REVOKE"))return privilege(true);
        if(c.take("COMMIT"))return "COMMIT";
        if(c.take("ROLLBACK")){review("Transaction behavior differs between Oracle implicit DDL commits and PostgreSQL. Review this rollback boundary.");return "ROLLBACK";}
        throw unsupported("No conversion rule for "+c.peekText()+"; original statement requires manual conversion");
    }
    private String createTable(List<Token> tokens){
        c.object();
        Table table=new SqlParser(source,target,schema).definition(tokens);String name=table.name().in(target);
        if(current.containsKey(name))throw error("Table "+name+" is already defined in this script");
        for(Key key:table.keys())if(key.reference()!=null){Table parent=current.get(key.reference().in(target));if(parent==null&&!key.reference().in(target).equals(name))review("Referenced table "+key.reference().in(target)+" must exist when this CREATE TABLE runs.");}
        store(table);messages.addAll(table.warnings());if(!table.warnings().isEmpty())review=true;c.p=tokens.size();
        List<String> definitions=new ArrayList<>();for(Column column:table.columns())definitions.add(columnSql(column));
        for(Key key:table.keys())definitions.add(constraint(key));
        return "CREATE TABLE "+q(table.name())+" (\n  "+String.join(",\n  ",definitions)+"\n)";
    }
    private String columnSql(Column column){return column.name().sql(target)+" "+column.type().sql(target)+(column.generated()?" GENERATED "+(column.always()?"ALWAYS":"BY DEFAULT")+" AS IDENTITY":"")+(column.defaultValue()==null?"":" DEFAULT "+column.defaultValue().sql())+(column.nullable()?"":" NOT NULL");}
    private String constraint(Key key){
        if(key.kind().equals("FOREIGN KEY"))return (key.name()==null?"":"CONSTRAINT "+key.name().sql(target)+" ")+"FOREIGN KEY ("+SqlWriter.names(key.columns(),target)+") REFERENCES "+q(key.reference())+" ("+SqlWriter.names(key.referenceColumns(),target)+")";
        return SqlWriter.constraint(key,target);
    }
    private String alter(){
        Name tableName=c.object();Table table=require(tableName);
        if(c.take("ADD")){
            c.take("COLUMN");List<List<Token>> additions=c.peek("(")?split(c.group(),","):List.of(c.rest());List<String> statements=new ArrayList<>();
            for(List<Token> addition:additions){
                boolean constraint=addition.getFirst().is("CONSTRAINT")||Set.of("PRIMARY","UNIQUE","CHECK","FOREIGN").stream().anyMatch(addition.getFirst()::is);
                if(constraint){
                    String existing=table.columns().stream().map(col->(col.name().quoted()?quote(col.name().value()):col.name().value())+" DECIMAL(10,0)").collect(Collectors.joining(", "));
                    // Parse just the constraint against known target columns without reinterpreting their source types.
                    List<Token> kt=SqlParser.lex("CREATE TABLE placeholder ("+existing+", "+raw(addition)+")");
                    Table parsed=new SqlParser(source,target,schema).definition(kt);Key key=parsed.keys().getLast();validate(table,key.columns());
                    List<Key> keys=new ArrayList<>(table.keys());keys.add(key);table=new Table(table.name(),table.columns(),List.copyOf(keys),table.warnings());
                    statements.add("ALTER TABLE "+q(tableName)+" ADD "+constraint(key));
                }else{
                    Table parsed=fragment(addition);Column column=parsed.columns().getFirst();if(find(table,column.name())!=null)throw error("Column already exists: "+column.name().in(target));
                    List<Column> cols=new ArrayList<>(table.columns());cols.add(column);List<Key> keys=new ArrayList<>(table.keys());keys.addAll(parsed.keys());
                    table=new Table(table.name(),List.copyOf(cols),List.copyOf(keys),table.warnings());statements.add("ALTER TABLE "+q(tableName)+" ADD "+(target==Dialect.POSTGRESQL?"COLUMN ":"")+columnSql(column));
                    for(Key key:parsed.keys())statements.add("ALTER TABLE "+q(tableName)+" ADD "+constraint(key));messages.addAll(parsed.warnings());
                }
            }
            store(table);return String.join(";\n",statements);
        }
        if(c.take("MODIFY")){
            if(target!=Dialect.POSTGRESQL)throw unsupported("MODIFY conversion currently targets PostgreSQL");
            List<Token> definition=c.peek("(")?c.group():c.rest();Table parsed=fragment(definition);Column changed=parsed.columns().getFirst();Column old=column(table,changed.name());
            Column merged=new Column(old.name(),changed.type(),old.nullable(),old.defaultValue(),old.generated(),old.always());
            if(definition.stream().anyMatch(t->t.is("DEFAULT")||t.is("NULL")||t.is("GENERATED")))throw unsupported("MODIFY with default/nullability/identity clauses requires separate ALTER rules");
            List<Column> cols=table.columns().stream().map(col->col.name().in(target).equals(old.name().in(target))?merged:col).toList();store(new Table(table.name(),cols,table.keys(),table.warnings()));
            review("Changing a column type may fail for existing data; no lossy USING cast is invented.");return "ALTER TABLE "+q(tableName)+" ALTER COLUMN "+old.name().sql(target)+" TYPE "+changed.type().sql(target);
        }
        if(c.take("RENAME")){
            if(c.take("COLUMN")){
                Name old=c.name();column(table,old);c.need("TO");Name next=c.name();if(find(table,next)!=null)throw error("Rename destination column already exists");
                if(table.keys().stream().anyMatch(k->k.columns().stream().anyMatch(n->n.in(target).equals(old.in(target)))))throw unsupported("Renaming a constrained column requires dependency remapping");
                List<Column> cols=table.columns().stream().map(col->col.name().in(target).equals(old.in(target))?new Column(next,col.type(),col.nullable(),col.defaultValue(),col.generated(),col.always()):col).toList();
                store(new Table(table.name(),cols,table.keys(),table.warnings()));return "ALTER TABLE "+q(tableName)+" RENAME COLUMN "+old.sql(target)+" TO "+next.sql(target);
            }c.need("TO");return rename(tableName,c.name());
        }
        if(c.take("DROP")){
            c.need("COLUMN");Name name=c.name();column(table,name);
            if(table.keys().stream().anyMatch(k->k.columns().stream().anyMatch(n->n.in(target).equals(name.in(target)))))throw unsupported("Dropping a constrained column requires explicit dependency handling");
            store(new Table(table.name(),table.columns().stream().filter(col->!col.name().in(target).equals(name.in(target))).toList(),table.keys(),table.warnings()));
            review("DROP COLUMN removes stored data.");return "ALTER TABLE "+q(tableName)+" DROP COLUMN "+name.sql(target);
        }
        throw unsupported("Unsupported ALTER TABLE clause: "+c.peekText());
    }
    private Table fragment(List<Token> definition){return new SqlParser(source,target,schema).definition(SqlParser.lex("CREATE TABLE placeholder ("+raw(definition)+")"));}
    private String createIndex(boolean unique){
        Name index=c.object();c.need("ON");Name name=c.object();Table table=require(name);List<List<Token>> terms=split(c.group(),",");List<String> sql=new ArrayList<>();
        for(List<Token> term:terms){SqlExpression.Result expr=expression(term,table);sql.add(expr.sql());}
        if(indexes.putIfAbsent(index.in(target),name.in(target))!=null)throw error("Duplicate index name: "+index.in(target));
        return "CREATE "+(unique?"UNIQUE ":"")+"INDEX "+index.sql(target)+" ON "+q(name)+" ("+String.join(", ",sql)+")";
    }
    private String sequence(){
        Name name=c.object();List<String> options=new ArrayList<>();
        while(!c.done()){
            if(c.take("START")){c.need("WITH");options.add("START WITH "+c.integer());}
            else if(c.take("INCREMENT")){c.need("BY");options.add("INCREMENT BY "+c.integer());}
            else if(c.take("CACHE"))options.add("CACHE "+c.integer());
            else if(c.take("NOCACHE"))options.add(target==Dialect.POSTGRESQL?"CACHE 1":"NOCACHE");
            else if(c.take("NOCYCLE"))options.add(target==Dialect.POSTGRESQL?"NO CYCLE":"NOCYCLE");
            else if(c.take("CYCLE"))options.add("CYCLE");
            else if(c.take("MINVALUE"))options.add("MINVALUE "+c.integer());
            else if(c.take("MAXVALUE"))options.add("MAXVALUE "+c.integer());
            else throw unsupported("Sequence option needs a mapping: "+c.peekText());
        }
        review("Oracle sequences can exceed PostgreSQL's signed 64-bit range; verify bounds and ownership.");
        return "CREATE SEQUENCE "+q(name)+(options.isEmpty()?"":" "+String.join(" ",options));
    }
    private String insert(List<Token> tokens){
        c.need("INSERT");c.need("INTO");c.object();SqlParser parser=new SqlParser(source,target,schema);
        Insert insert=parser.data(tokens);messages.addAll(parser.expressionWarnings());if(!messages.isEmpty())review=true;
        Table table=require(insert.table());List<Name> cols=insert.columns().isEmpty()?table.columns().stream().map(Column::name).toList():insert.columns();validate(table,cols);
        for(List<Value> row:insert.rows())validateValues(table,cols,row);c.p=tokens.size();
        List<String> sql=new ArrayList<>();for(List<Value> row:insert.rows())sql.add(SqlWriter.insert(table,cols,row,schema,target));return String.join(";\n",sql);
    }
    private void validateValues(Table table,List<Name> columns,List<Value> values){
        if(columns.size()!=values.size())throw error("INSERT column/value count differs for "+table.name().in(target));
        Set<String> seen=new HashSet<>();
        for(int i=0;i<columns.size();i++){
            Column col=column(table,columns.get(i));if(!seen.add(col.name().in(target)))throw error("Duplicate INSERT column "+col.name().in(target));
            if(col.always())throw error("Explicit value for GENERATED ALWAYS identity column "+col.name().in(target)+" needs an explicit override policy");
            if(!col.nullable()&&values.get(i).sql().equals("NULL"))throw error("NULL supplied for NOT NULL column "+table.name().in(target)+"."+col.name().in(target)+". Oracle empty strings are NULL.");
        }
        for(Column col:table.columns())if(!seen.contains(col.name().in(target))&&!col.nullable()&&!col.generated()&&col.defaultValue()==null)throw error("Required column omitted from INSERT: "+col.name().in(target));
    }
    private String update(){
        Name name=c.object();Table table=require(name);c.need("SET");List<String> assignments=new ArrayList<>();
        do{Name column=c.name();column(table,column);c.need("=");SqlExpression.Result value=c.expr();validate(table,value.columns());assignments.add(column.sql(target)+" = "+value.sql());}while(c.take(","));
        String where="";if(c.take("WHERE")){SqlExpression.Result expr=c.expr();validate(table,expr.columns());where=" WHERE "+expr.sql();}
        review("UPDATE changes existing rows; the original statement order is preserved.");return "UPDATE "+q(name)+" SET "+String.join(", ",assignments)+where;
    }
    private String delete(){
        c.need("FROM");Name name=c.object();Table table=require(name);String where="";if(c.take("WHERE")){SqlExpression.Result expr=c.expr();validate(table,expr.columns());where=" WHERE "+expr.sql();}
        review("DELETE removes matching rows; review the predicate.");return "DELETE FROM "+q(name)+where;
    }
    private String drop(){
        String kind=c.next().text().toUpperCase(Locale.ROOT);if(!Set.of("TABLE","INDEX","SEQUENCE").contains(kind))throw unsupported("DROP "+kind+" requires a conversion rule");Name name=c.object();String cascade="";
        if(c.take("CASCADE")){c.take("CONSTRAINTS");cascade=" CASCADE";review("PostgreSQL CASCADE can remove more dependent objects than Oracle CASCADE CONSTRAINTS. Inspect dependencies first.");}
        if(kind.equals("TABLE")){require(name);current.remove(name.in(target));}if(kind.equals("INDEX"))indexes.remove(name.in(target));
        review("DROP "+kind+" is destructive and stays at its original position in the script.");return "DROP "+kind+" "+q(name)+cascade;
    }
    private String rename(Name old,Name next){
        Table table=require(old);if(current.containsKey(next.in(target)))throw error("Rename destination table already exists");current.remove(old.in(target));
        history.remove(old.in(target));store(new Table(next,table.columns(),table.keys(),table.warnings()));
        for(Table dependent:List.copyOf(current.values())){
            List<Key> keys=dependent.keys().stream().map(k->k.reference()!=null&&k.reference().in(target).equals(old.in(target))?new Key(k.kind(),k.columns(),next,k.referenceColumns(),k.name(),k.expression()):k).toList();
            store(new Table(dependent.name(),dependent.columns(),keys,dependent.warnings()));
        }
        review("Table rename changes names referenced by later statements and application queries.");return "ALTER TABLE "+q(old)+" RENAME TO "+next.sql(target);
    }
    private String comment(){
        c.need("ON");boolean table=c.take("TABLE");if(!table)c.need("COLUMN");Name name=c.name();Table model=require(name);String object=q(name);
        if(!table){c.need(".");Name column=c.name();column(model,column);object+="."+column.sql(target);}c.need("IS");Token value=c.next();if(value.kind()!='s'&&!value.is("NULL"))throw error("COMMENT requires a string or NULL");
        return "COMMENT ON "+(table?"TABLE ":"COLUMN ")+object+" IS "+(value.is("NULL")?"NULL":SqlExpression.literal(value.text()));
    }
    private String privilege(boolean revoke){
        List<String> privileges=new ArrayList<>();do{String privilege=c.next().text().toUpperCase(Locale.ROOT);if(!Set.of("SELECT","INSERT","UPDATE","DELETE","REFERENCES").contains(privilege))throw unsupported("Privilege mapping required: "+privilege);privileges.add(privilege);}while(c.take(","));
        c.need("ON");Name table=c.object();require(table);c.need(revoke?"FROM":"TO");Name role=c.name();review("Verify that target role "+role.in(target)+" exists and that these permission changes are intended.");
        return (revoke?"REVOKE ":"GRANT ")+String.join(", ",privileges)+" ON "+q(table)+(revoke?" FROM ":" TO ")+role.sql(target);
    }
    private record Query(String sql,List<Name> columns){}
    private Query query(Cursor query){
        query.need("SELECT");List<String> fields=new ArrayList<>();List<Name> columns=new ArrayList<>();int number=0;
        do{SqlExpression.Result expr=query.expr();if(!expr.columns().isEmpty())throw unsupported("Only constant SELECT sources are supported for multi-insert/MERGE");Name alias=query.take("AS")?query.name():new Name("migration_value_"+(++number),false);fields.add(expr.sql()+" AS "+alias.sql(target));columns.add(alias);}while(query.take(","));
        query.need("FROM");query.need("DUAL");query.end();return new Query("SELECT "+String.join(", ",fields),List.copyOf(columns));
    }
    private record Branch(List<Token> condition,Name table,List<Name> columns,List<List<Token>> values,boolean otherwise,int group){}
    private String multiInsert(boolean first){
        if(source!=Dialect.ORACLE||target!=Dialect.POSTGRESQL)throw unsupported("INSERT ALL/FIRST conversion currently supports Oracle to PostgreSQL");
        List<Branch> branches=new ArrayList<>();List<Token> condition=List.of();boolean otherwise=false;int group=0;
        while(!c.peek("SELECT")){
            if(c.take("WHEN")){condition=c.until("THEN");c.need("THEN");otherwise=false;group++;}
            else if(c.take("ELSE")){condition=List.of();otherwise=true;group++;}
            c.need("INTO");Name name=c.object();List<Name> columns=c.names();c.need("VALUES");List<List<Token>> values=split(c.group(),",");branches.add(new Branch(condition,name,columns,values,otherwise,group));
            if(c.done())throw error("INSERT ALL/FIRST requires a source SELECT");
        }
        Query sourceQuery=query(new Cursor(c.rest()));List<String> ctes=new ArrayList<>();ctes.add("\"__migration_source\" AS MATERIALIZED ("+sourceQuery.sql()+")");Map<Integer,String> earlier=new LinkedHashMap<>();int number=0;
        for(Branch branch:branches){
            Table table=require(branch.table());validate(table,branch.columns());List<String> values=new ArrayList<>();
            for(List<Token> value:branch.values()) {SqlExpression.Result expr=expression(value,null);validateNames(sourceQuery.columns(),expr.columns(),"source SELECT");values.add(expr.sql());}
            if(values.size()!=branch.columns().size())throw error("Multi-insert column/value count differs");
            String conditionSql="TRUE";
            if(!branch.condition().isEmpty()){SqlExpression.Result expr=expression(branch.condition(),null);validateNames(sourceQuery.columns(),expr.columns(),"source SELECT");conditionSql=expr.sql();}
            List<String> filters=new ArrayList<>();filters.add(conditionSql);
            if(first||branch.otherwise())for(var previous:earlier.entrySet())if(previous.getKey()!=branch.group())filters.add("("+previous.getValue()+") IS NOT TRUE");
            if(!branch.condition().isEmpty())earlier.put(branch.group(),conditionSql);
            ctes.add("\"__migration_insert_"+(++number)+"\" AS (INSERT INTO "+q(branch.table())+" ("+SqlWriter.names(branch.columns(),target)+") SELECT "+String.join(", ",values)+" FROM \"__migration_source\" WHERE "+String.join(" AND ",filters)+" RETURNING 1)");
        }
        review("Multi-table insert uses one materialized source and data-modifying CTEs. Validate constraint interactions; sibling CTE execution order is unspecified.");
        return "WITH "+String.join(",\n",ctes)+"\nSELECT 1";
    }
    private String merge(){
        if(source!=Dialect.ORACLE||target!=Dialect.POSTGRESQL)throw unsupported("MERGE conversion currently supports Oracle to PostgreSQL 15+");
        c.need("INTO");Name name=c.object();Table table=require(name);Name alias=c.name();c.need("USING");Query query=query(new Cursor(c.group()));Name sourceAlias=c.name();c.need("ON");
        SqlExpression.Result on=expression(c.group(),null);List<Name> available=new ArrayList<>(table.columns().stream().map(Column::name).toList());available.addAll(query.columns());validateNames(available,on.columns(),"MERGE source/target");
        List<String> clauses=new ArrayList<>();
        while(c.take("WHEN")){
            boolean not=c.take("NOT");c.need("MATCHED");c.need("THEN");
            if(!not){c.need("UPDATE");c.need("SET");List<String> assignments=new ArrayList<>();do{Name col=c.name();if(c.take(".")){if(!col.in(target).equals(alias.in(target)))throw error("Unexpected target alias in MERGE assignment");col=c.name();}column(table,col);c.need("=");SqlExpression.Result value=c.expr();validateNames(available,value.columns(),"MERGE source/target");assignments.add(col.sql(target)+" = "+value.sql());}while(c.take(","));clauses.add("WHEN MATCHED THEN UPDATE SET "+String.join(", ",assignments));}
            else{c.need("INSERT");List<Name> columns=c.names();validate(table,columns);c.need("VALUES");List<String> values=new ArrayList<>();for(List<Token> expression:split(c.group(),",")){SqlExpression.Result value=expression(expression,null);validateNames(available,value.columns(),"MERGE source/target");values.add(value.sql());}if(values.size()!=columns.size())throw error("MERGE insert column/value count differs");clauses.add("WHEN NOT MATCHED THEN INSERT ("+SqlWriter.names(columns,target)+") VALUES ("+String.join(", ",values)+")");}
        }
        if(clauses.isEmpty())throw error("MERGE has no WHEN clauses");review("Native MERGE requires PostgreSQL 15 or later. Verify source uniqueness and matching behavior.");
        return "MERGE INTO "+q(name)+" AS "+alias.sql(target)+"\nUSING ("+query.sql()+") AS "+sourceAlias.sql(target)+"\nON "+on.sql()+"\n"+String.join("\n",clauses);
    }
    private SqlExpression.Result expression(List<Token> tokens,Table table){SqlExpression.Result result=SqlExpression.parse(tokens,0,source,target,schema);if(result.next()!=tokens.size())throw unsupported("Unsupported trailing expression syntax");if(table!=null)validate(table,result.columns());messages.addAll(result.warnings());return result;}
    private void validate(Table table,List<Name> names){for(Name name:names)column(table,name);}
    private void validateNames(List<Name> available,List<Name> requested,String object){for(Name name:requested)if(available.stream().noneMatch(n->n.in(target).equals(name.in(target))))throw error("Unknown column "+object+"."+name.in(target));}
    private Column column(Table table,Name name){Column result=find(table,name);if(result==null)throw error("Unknown column "+table.name().in(target)+"."+name.in(target)+"; it is not defined at this statement");return result;}
    private Column find(Table table,Name name){return table.columns().stream().filter(col->col.name().in(target).equals(name.in(target))).findFirst().orElse(null);}
    private Table require(Name name){Table table=current.get(name.in(target));if(table==null)throw error((failedTables.contains(name.in(target))?"Blocked by earlier failed CREATE TABLE: ":"Table is not defined at this statement: ")+name.in(target));return table;}
    private void store(Table table){current.put(table.name().in(target),table);history.put(table.name().in(target),table);}
    private String q(Name name){return qualified(schema,name,target);}
    private void review(String message){review=true;messages.add(message);}
    private static IllegalArgumentException error(String text){return new IllegalArgumentException(text);}
    private static Unsupported unsupported(String text){return new Unsupported(text);}
    private static final class Unsupported extends IllegalArgumentException{Unsupported(String text){super(text);}}
    static String raw(List<Token> tokens){return tokens.stream().map(t->t.kind()=='s'?SqlExpression.literal(t.text()):t.kind()=='i'?quote(t.text()):t.text()).collect(Collectors.joining(" "));}
    static List<List<Token>> split(List<Token> tokens,String delimiter){
        List<List<Token>> result=new ArrayList<>();List<Token> part=new ArrayList<>();int depth=0;
        for(Token token:tokens){if(token.is("("))depth++;if(token.is(")"))depth--;if(token.is(delimiter)&&(delimiter.equals(";")||depth==0)){if(!part.isEmpty())result.add(List.copyOf(part));part.clear();}else part.add(token);}
        if(!part.isEmpty())result.add(List.copyOf(part));return result;
    }
    private final class Cursor{
        final List<Token> tokens;int p;
        Cursor(List<Token> tokens){this.tokens=tokens;}
        boolean done(){return p>=tokens.size();}
        String peekText(){return done()?"end":tokens.get(p).kind()=='s'?"[literal]":tokens.get(p).text();}
        boolean peek(String value){return !done()&&tokens.get(p).is(value);}
        boolean take(String value){if(peek(value)){p++;return true;}return false;}
        void need(String value){if(!take(value))throw error("Expected "+value+" near "+peekText());}
        Token next(){if(done())throw error("Unexpected end of statement");return tokens.get(p++);}
        Name name(){Token token=next();if(token.kind()!='w'&&token.kind()!='i')throw error("Expected identifier");return new Name(token.text(),token.kind()=='i');}
        Name object(){Name first=name();if(take(".")){Name last=name();if(sourceSchema!=null&&!sourceSchema.equals(first.in(source)))throw error("Multiple source schemas need explicit mappings before conversion");sourceSchema=first.in(source);review("Source schema "+first.in(source)+" maps to target schema "+schema+".");return last;}return first;}
        List<Name> names(){need("(");List<Name> values=new ArrayList<>();do{values.add(name());}while(take(","));need(")");return values;}
        List<Token> group(){need("(");int start=p,depth=1;while(!done()){Token token=next();if(token.is("("))depth++;if(token.is(")")){depth--;if(depth==0)return List.copyOf(tokens.subList(start,p-1));}}throw error("Unclosed parenthesized expression");}
        List<Token> rest(){List<Token> result=List.copyOf(tokens.subList(p,tokens.size()));p=tokens.size();return result;}
        List<Token> until(String word){int start=p,depth=0;while(!done()){Token token=tokens.get(p);if(depth==0&&token.is(word))break;if(token.is("("))depth++;if(token.is(")"))depth--;p++;}return List.copyOf(tokens.subList(start,p));}
        String integer(){String sign=take("-")?"-":"";Token token=next();if(token.kind()!='n'||!token.text().matches("[0-9]+"))throw error("Expected integer option");return sign+token.text();}
        SqlExpression.Result expr(){SqlExpression.Result result=SqlExpression.parse(tokens,p,source,target,schema);p=result.next();messages.addAll(result.warnings());return result;}
        void end(){if(!done())throw unsupported("Unsupported trailing syntax near "+peekText());}
    }
}
