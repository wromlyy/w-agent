package dev.wagent;
import org.json.*;
import java.util.*;

public final class Roles {
    public static JSONArray presets()throws Exception{
        JSONArray all=new JSONArray();String[][] rows={
            {"code","Code-агент","Читай README и AGENTS.md. Составь план, вноси небольшие diff. После правок запускай тесты и сборку через GitHub Actions; получай реальный артефакт. В итоговом отчёте отделяй выполненное, проверенное и ограничения.","project,files,shell_exec,service,github_actions,memory","high"},
            {"python","Python-разработчик","Пиши понятный Python. Проверь зависимости и среду, запускай тесты и проверку синтаксиса. Для отсутствующего Python используй workflow репозитория, не выдумывай результат.","project,files,shell_exec,service,github_actions,memory","high"},
            {"frontend","Frontend","Изучи существующие компоненты и стили, соблюдай дизайн. Используй небольшие правки. Проверь адаптивность и тесты через workflow.","project,files,shell_exec,service,github_actions,memory","high"},
            {"debugger","Debugger","Сначала воспроизведение и факты из логов, затем причина, минимальная правка и проверка. Не исправляй предположения без доказательств.","project,files,shell_exec,service,github_actions,device,memory","high"},
            {"reviewer","Reviewer","Только читай и анализируй. Найди ошибки и регрессии, приводи файл и конкретное доказательство. Не изменяй файлы и не запускай изменяющие команды.","project,files,shell_exec,service,github_actions,device","high"},
            {"architect","Architect","Исследуй проект и предложи архитектуру и компромиссы. Не меняй проект: подготовь проверяемый план.","project,files,service,github_actions","high"},
            {"phone","Phone Operator","Управляй телефоном. Перед действием читай экран, указывай ожидаемый результат и проверяй его. Не повторяй неуспешные действия.","device,files,service,memory","medium"},
            {"researcher","Researcher","Ищи первичные источники, проверяй даты и факты. Ссылайся на фактически прочитанные страницы.","device,service,files,web,memory","medium"},
            {"writer","Writer / Editor","Пиши и редактируй тексты на языке пользователя, сохраняй стиль и смысл. Используй файлы результатов для выдачи материалов.","project,files,memory","medium"},
            {"general","Универсальный","Выполняй задачу инструментами и проверяй фактический результат.","device,project,files,shell_exec,service,github_actions,memory,web","auto"}};
        for(String[] row:rows)all.put(new JSONObject().put("id",row[0]).put("name",row[1]).put("prompt",row[2]).put("tools",new JSONArray(Arrays.asList(row[3].split(",")))).put("effort",row[4]).put("modelProfile","").put("trust","read_auto").put("readOnly",Arrays.asList("reviewer","architect").contains(row[0])));return all;
    }
    public static JSONObject active(JSONObject settings)throws Exception{
        JSONArray roles=settings.optJSONArray("roles");if(roles==null||roles.length()==0)roles=presets();String id=settings.optString("activeRole","general");for(int i=0;i<roles.length();i++)if(id.equals(roles.getJSONObject(i).optString("id")))return roles.getJSONObject(i);throw new IllegalArgumentException("Выбранная роль не найдена");
    }
    public static void validate(JSONArray roles)throws Exception{
        if(roles==null||roles.length()>40)throw new IllegalArgumentException("Ролей должно быть не более 40");Set<String> ids=new HashSet<>();for(int i=0;i<roles.length();i++){JSONObject role=roles.getJSONObject(i);if(role.getString("id").trim().isEmpty()||!ids.add(role.getString("id")))throw new IllegalArgumentException("ID роли пустой или повторяется");role.getString("name");if(role.getString("prompt").length()>20000)throw new IllegalArgumentException("Промпт роли слишком большой");JSONArray tools=role.getJSONArray("tools");for(int j=0;j<tools.length();j++)if(!Arrays.asList("device","project","files","shell_exec","service","github_actions","memory","web").contains(tools.getString(j)))throw new IllegalArgumentException("Неизвестный инструмент роли");if(!Arrays.asList("ask_all","read_auto","sandbox").contains(role.optString("trust","read_auto")))throw new IllegalArgumentException("Неизвестный режим доверия");Models.effort(role);}
    }
    public static boolean readOnlyCall(String name,JSONObject a){
        String action=a.optString("action");switch(name){
            case "device":return Arrays.asList("read_screen","list_apps","wait").contains(action);
            case "files":return Arrays.asList("folders","list","read","attach").contains(action);
            case "project":return Arrays.asList("read_file","list_dir","grep","instructions").contains(action);
            case "shell_exec":return ShellPolicy.autoRead(a.optString("cmd"));
            case "github_actions":return Arrays.asList("workflows","runs","run","jobs","artifacts").contains(action);
            case "memory":return "list".equals(action);
            case "service":return (!a.has("operation")&&"GET".equalsIgnoreCase(a.optString("method","GET")))||Arrays.asList("tools/list","resources/list","resources/read","prompts/list","prompts/get").contains(a.optString("operation"));
            case "web":return true;
            default:return false;
        }
    }
    public static boolean permits(JSONObject role,String name,JSONObject args)throws Exception{
        JSONArray tools=role.getJSONArray("tools");boolean found=false;for(int i=0;i<tools.length();i++)if(name.equals(tools.getString(i)))found=true;
        return found&&(!role.optBoolean("readOnly")||readOnlyCall(name,args));
    }
    public static JSONArray filter(JSONObject role,JSONArray definitions)throws Exception{JSONArray out=new JSONArray();JSONArray names=role.getJSONArray("tools");for(int i=0;i<definitions.length();i++){JSONObject d=definitions.getJSONObject(i);String name=d.getJSONObject("function").getString("name");for(int j=0;j<names.length();j++)if(name.equals(names.getString(j)))out.put(d);}return out;}
}
