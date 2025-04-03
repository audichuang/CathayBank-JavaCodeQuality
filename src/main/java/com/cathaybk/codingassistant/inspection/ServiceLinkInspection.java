package com.cathaybk.codingassistant.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 檢查Service類是否有關聯的Controller 電文代號註解
 */
public class ServiceLinkInspection extends AbstractBaseJavaLocalInspectionTool {

    // 定義電文代號的正則表達式模式 - 匹配整行內容，包括電文代號和描述
    private static final Pattern API_ID_PATTERN = Pattern.compile("([A-Za-z0-9]+-[A-Za-z0-9]+-[A-Za-z0-9]+.*)");

    @NotNull
    @Override
    public String getShortName() {
        return "ServiceLinkInspection";
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(PsiClass aClass) {
                // 只檢查Service或ServiceImpl類
                String className = aClass.getName();
                System.out.println("檢查類: " + className);

                if (className == null || !(className.contains("Service") || className.contains("ServiceImpl"))) {
                    return;
                }

                System.out.println("檢查Service類: " + className);

                // 檢查是否已經有電文代號 Javadoc註解
                PsiDocComment docComment = aClass.getDocComment();
                boolean hasApiIdFormat = false;

                if (docComment != null) {
                    String docText = docComment.getText();
                    hasApiIdFormat = API_ID_PATTERN.matcher(docText).find();
                    System.out.println("Service類 " + className + " 的文檔註解: " + docText);
                    System.out.println("已有電文代號格式? " + hasApiIdFormat);
                } else {
                    System.out.println("Service類 " + className + " 沒有文檔註解");
                }

                if (hasApiIdFormat) {
                    return;
                }

                // 查找使用此Service的Controller方法
                Map<String, String> controllerApiIds = findControllerApiIds(aClass);

                // 如果是實現類，且未找到相關電文代號，嘗試查找對應接口的電文代號
                if (controllerApiIds.isEmpty() && className.contains("Impl")) {
                    System.out.println("嘗試查找對應接口的電文代號...");
                    // 從實現類名推斷接口名
                    String possibleInterfaceName = className.replace("Impl", "");

                    // 檢查實現的接口
                    for (PsiClassType interfaceType : aClass.getImplementsListTypes()) {
                        PsiClass interfaceClass = interfaceType.resolve();
                        if (interfaceClass != null && interfaceClass.getName() != null) {
                            System.out.println("檢查實現的接口: " + interfaceClass.getName());

                            // 查找接口的電文代號
                            Map<String, String> interfaceApiIds = findControllerApiIds(interfaceClass);
                            if (!interfaceApiIds.isEmpty()) {
                                System.out.println("使用接口 " + interfaceClass.getName() + " 的電文代號");
                                controllerApiIds.putAll(interfaceApiIds);
                                break;
                            }

                            // 檢查接口的Javadoc註解
                            PsiDocComment interfaceDoc = interfaceClass.getDocComment();
                            if (interfaceDoc != null) {
                                String interfaceDocText = interfaceDoc.getText();
                                Matcher matcher = API_ID_PATTERN.matcher(interfaceDocText);
                                if (matcher.find()) {
                                    String apiId = matcher.group(1);
                                    System.out.println("從接口獲取電文代號: " + apiId);
                                    controllerApiIds.put(interfaceClass.getName(), apiId);
                                    break;
                                }
                            }
                        }
                    }
                }

                System.out.println("找到關聯的Controller 電文代號s: " + controllerApiIds.size() + " 個");
                for (Map.Entry<String, String> entry : controllerApiIds.entrySet()) {
                    System.out.println("方法: " + entry.getKey() + ", 電文代號: " + entry.getValue());
                }

                if (!controllerApiIds.isEmpty()) {
                    System.out.println("註冊問題: Service類 " + className + " 需要添加電文代號註解");
                    holder.registerProblem(
                            aClass.getNameIdentifier() != null ? aClass.getNameIdentifier() : aClass,
                            "Service類可能需要添加來自Controller的電文代號註解",
                            new AddServiceApiIdQuickFix(controllerApiIds));
                } else {
                    System.out.println("未找到關聯的Controller 電文代號，不註冊問題");
                }
            }
        };
    }

    /**
     * 查找使用此Service的Controller方法及其電文代號
     */
    private Map<String, String> findControllerApiIds(PsiClass serviceClass) {
        Map<String, String> result = new HashMap<>();
        Project project = serviceClass.getProject();
        System.out.println("搜索使用 " + serviceClass.getName() + " 的Controller方法");

        try {
            // 使用ReferencesSearch尋找所有引用
            Collection<PsiReference> references = ReferencesSearch.search(serviceClass).findAll();
            System.out.println("找到 " + references.size() + " 個引用");

            // 檢查方法引用
            checkMethodReferences(references, serviceClass, result);

            // 檢查變量和字段引用
            checkVariableReferences(references, serviceClass, result);

            // 檢查方法參數引用
            checkParameterReferences(references, serviceClass, result);

        } catch (Exception e) {
            System.out.println("搜索引用時出錯: " + e.getMessage());
            e.printStackTrace();
        }

        // 如果沒有找到引用，嘗試搜索Java字面量
        if (result.isEmpty() && serviceClass.getName() != null) {
            System.out.println("沒有找到直接引用，嘗試搜索字段");
            // 如果Service名稱是AbcService，搜索字符串"abcService"
            String serviceFieldName = serviceClass.getName().substring(0, 1).toLowerCase()
                    + serviceClass.getName().substring(1);

            // 直接搜索更寬泛的controller包名
            searchForServiceUsageInControllers(project, serviceFieldName, result);

            // 如果仍找不到，嘗試直接檢查所有的 Controller 方法調用
            if (result.isEmpty()) {
                System.out.println("嘗試檢查 Controller 調用 Service 的方法...");
                searchAllControllerMethods(project, serviceClass, result);
            }
        }

        return result;
    }

    /**
     * 檢查所有方法引用
     */
    private void checkMethodReferences(Collection<PsiReference> references, PsiClass serviceClass,
            Map<String, String> result) {
        for (PsiReference reference : references) {
            PsiElement element = reference.getElement();
            System.out.println("檢查引用: " + element.getText());

            // 找到包含此引用的方法
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (method == null) {
                System.out.println("引用不在方法內，跳過");
                continue;
            }

            checkControllerMethod(method, serviceClass, result);
        }
    }

    /**
     * 檢查變量和字段引用
     */
    private void checkVariableReferences(Collection<PsiReference> references, PsiClass serviceClass,
            Map<String, String> result) {
        for (PsiReference reference : references) {
            PsiElement element = reference.getElement();

            // 檢查是否是字段或局部變量聲明
            PsiVariable variable = PsiTreeUtil.getParentOfType(element, PsiVariable.class);
            if (variable != null) {
                System.out.println("找到變量聲明: " + variable.getName());

                // 找到使用此變量的所有方法
                Collection<PsiReference> variableReferences = ReferencesSearch.search(variable).findAll();
                for (PsiReference varRef : variableReferences) {
                    PsiElement varElement = varRef.getElement();
                    PsiMethod method = PsiTreeUtil.getParentOfType(varElement, PsiMethod.class);
                    if (method != null) {
                        checkControllerMethod(method, serviceClass, result);
                    }
                }
            }
        }
    }

    /**
     * 檢查參數引用
     */
    private void checkParameterReferences(Collection<PsiReference> references, PsiClass serviceClass,
            Map<String, String> result) {
        for (PsiReference reference : references) {
            PsiElement element = reference.getElement();

            // 檢查是否是參數聲明
            PsiParameter parameter = PsiTreeUtil.getParentOfType(element, PsiParameter.class);
            if (parameter != null) {
                System.out.println("找到參數聲明: " + parameter.getName());

                // 找到包含此參數的方法
                PsiMethod method = PsiTreeUtil.getParentOfType(parameter, PsiMethod.class);
                if (method != null) {
                    checkControllerMethod(method, serviceClass, result);
                }
            }
        }
    }

    /**
     * 檢查方法是否是Controller方法並含有電文代號
     */
    private void checkControllerMethod(PsiMethod method, PsiClass serviceClass, Map<String, String> result) {
        System.out.println("檢查方法: " + method.getName());

        // 檢查方法是否在Controller中
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            System.out.println("方法沒有包含類，跳過");
            return;
        }

        String className = containingClass.getName();
        if (className == null) {
            System.out.println("類名為空，跳過");
            return;
        }

        // 檢查是否是Controller類 (擴大匹配範圍)
        boolean isController = className.contains("Controller") ||
                (containingClass.getQualifiedName() != null &&
                        containingClass.getQualifiedName().contains("controller"));

        if (!isController) {
            System.out.println("方法不在Controller中，跳過: " + className);
            return;
        }

        System.out.println("發現Controller類: " + className);

        // 檢查方法中使用了service的方法調用 - 對整個方法文本進行檢查，更寬鬆的檢測
        String methodText = method.getText();
        String serviceNameLowercaseFirst = serviceClass.getName().substring(0, 1).toLowerCase() +
                serviceClass.getName().substring(1);
        boolean usesService = methodText.contains(serviceNameLowercaseFirst);

        if (!usesService) {
            System.out.println("方法未直接使用Service，跳過");
            return;
        }

        System.out.println("方法中使用了Service");

        // 檢查方法是否有電文代號註解
        PsiDocComment docComment = method.getDocComment();
        if (docComment != null) {
            String docText = docComment.getText();
            System.out.println("Controller方法的文檔註解: " + docText);
            Matcher matcher = API_ID_PATTERN.matcher(docText);
            if (matcher.find()) {
                String apiId = matcher.group(1);
                System.out.println("找到電文代號: " + apiId + ", 方法: " + method.getName());
                result.put(method.getName(), apiId);
            } else {
                System.out.println("未找到電文代號格式");
            }
        } else {
            System.out.println("Controller方法沒有文檔註解");
        }
    }

    /**
     * 檢查代碼塊中是否使用了特定的Service
     */
    private boolean checkCodeBlockForServiceUse(PsiCodeBlock codeBlock, String serviceName) {
        if (serviceName == null)
            return false;

        for (PsiStatement statement : codeBlock.getStatements()) {
            String statementText = statement.getText();
            String fieldName = serviceName.substring(0, 1).toLowerCase() + serviceName.substring(1);
            if (statementText.contains(fieldName)) {
                System.out.println("代碼塊中使用了Service: " + fieldName + " 在語句: " + statementText);
                return true;
            }
        }
        return false;
    }

    /**
     * 搜索使用Service的Controller
     */
    private void searchForServiceUsageInControllers(Project project, String serviceFieldName,
            Map<String, String> result) {
        System.out.println("搜索使用 " + serviceFieldName + " 字段的Controller");
        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

            // 搜索Controller包下的類
            PsiClass[] controllers = psiFacade.findClasses("*.controller.*", scope);
            System.out.println("找到 " + controllers.length + " 個controller包下的類");

            if (controllers.length == 0) {
                // 如果找不到，嘗試直接按命名規則搜索
                controllers = psiFacade.findClasses("*Controller", scope);
                System.out.println("找到 " + controllers.length + " 個名稱包含Controller的類");
            }

            checkControllersForServiceUsage(controllers, serviceFieldName, result);
        } catch (Exception e) {
            System.out.println("搜索Controller時出錯: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 檢查控制器類中是否使用了服務
     */
    private void checkControllersForServiceUsage(PsiClass[] controllers, String serviceFieldName,
            Map<String, String> result) {
        for (PsiClass controller : controllers) {
            System.out.println("檢查Controller: " + controller.getName());

            // 檢查整個類的文本是否包含服務名
            String classText = controller.getText();
            if (classText.contains(serviceFieldName)) {
                System.out.println("Controller文本中包含Service引用: " + serviceFieldName);

                // 檢查所有方法
                for (PsiMethod method : controller.getMethods()) {
                    if (hasAPIMapping(method)) {
                        System.out.println("檢查API方法: " + method.getName());

                        // 檢查方法是否使用了service
                        String methodText = method.getText();
                        if (methodText.contains(serviceFieldName)) {
                            System.out.println("方法 " + method.getName() + " 使用了 " + serviceFieldName);

                            // 檢查電文代號
                            PsiDocComment docComment = method.getDocComment();
                            if (docComment != null) {
                                String docText = docComment.getText();
                                Matcher matcher = API_ID_PATTERN.matcher(docText);
                                if (matcher.find()) {
                                    String apiId = matcher.group(1);
                                    System.out.println("找到電文代號: " + apiId + ", 方法: " + method.getName());
                                    result.put(method.getName(), apiId);
                                } else {
                                    System.out.println("未找到電文代號格式");
                                }
                            } else {
                                System.out.println("API方法沒有文檔註解");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 直接搜索所有Controller方法
     */
    private void searchAllControllerMethods(Project project, PsiClass serviceClass, Map<String, String> result) {
        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

            // 搜索所有Controller
            PsiClass[] controllers = psiFacade.findClasses("*Controller", scope);
            System.out.println("搜索所有Controller方法，找到 " + controllers.length + " 個Controller類");

            String serviceClassName = serviceClass.getName();
            // 服務類名的首字母小寫版本 (如AbcService -> abcService)
            String serviceFieldName = serviceClassName.substring(0, 1).toLowerCase() +
                    serviceClassName.substring(1);

            for (PsiClass controller : controllers) {
                System.out.println("檢查Controller: " + controller.getName());

                // 檢查所有方法
                for (PsiMethod method : controller.getMethods()) {
                    if (method.getText().contains(serviceFieldName) ||
                            method.getText().contains(serviceClassName)) {

                        System.out.println("找到使用Service的方法: " + method.getName());

                        // 檢查是否有API Mapping註解
                        if (hasAPIMapping(method)) {
                            System.out.println("該方法有API映射");

                            // 檢查電文代號
                            PsiDocComment docComment = method.getDocComment();
                            if (docComment != null) {
                                String docText = docComment.getText();
                                Matcher matcher = API_ID_PATTERN.matcher(docText);
                                if (matcher.find()) {
                                    String apiId = matcher.group(1);
                                    System.out.println("找到電文代號: " + apiId + ", 方法: " + method.getName());
                                    result.put(method.getName(), apiId);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("搜索所有Controller方法時出錯: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 檢查方法是否有API映射註解
     */
    private boolean hasAPIMapping(PsiMethod method) {
        for (PsiAnnotation annotation : method.getModifierList().getAnnotations()) {
            String name = annotation.getQualifiedName();
            if (name != null && (name.endsWith("Mapping") || name.contains("RequestMapping") ||
                    name.contains("GetMapping") || name.contains("PostMapping"))) {
                System.out.println("方法有API映射: " + name + " 在方法: " + method.getName());
                return true;
            }
        }
        return false;
    }

    /**
     * 快速修復：添加Service的電文代號 Javadoc註解
     */
    private static class AddServiceApiIdQuickFix implements LocalQuickFix {
        private final Map<String, String> controllerApiIds;

        public AddServiceApiIdQuickFix(Map<String, String> controllerApiIds) {
            this.controllerApiIds = controllerApiIds;
        }

        @NotNull
        @Override
        public String getName() {
            return "添加來自Controller的電文代號註解";
        }

        @NotNull
        @Override
        public String getFamilyName() {
            return getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            try {
                PsiElement element = descriptor.getPsiElement();
                if (!(element instanceof PsiIdentifier)) {
                    System.out.println("元素不是PsiIdentifier，無法應用修復");
                    return;
                }

                PsiClass aClass = (PsiClass) element.getParent();
                System.out.println("應用修復到類: " + aClass.getName());

                PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

                // 獲取Controller方法的完整文檔
                String methodName = controllerApiIds.keySet().iterator().next();
                String fullApiId = controllerApiIds.values().iterator().next();
                System.out.println("使用電文代號: " + fullApiId + ", 來自方法: " + methodName);

                // 創建Javadoc註解 - 保留完整的電文代號行，包括描述
                StringBuilder docText = new StringBuilder("/**\n");
                docText.append(" * ").append(fullApiId).append("\n");
                docText.append(" */");

                PsiDocComment docComment = factory.createDocCommentFromText(docText.toString());
                System.out.println("創建的文檔註解: " + docComment.getText());

                // 如果已有註解則替換，否則新增
                PsiDocComment existingComment = aClass.getDocComment();
                if (existingComment != null) {
                    System.out.println("替換現有註解");
                    existingComment.replace(docComment);
                } else {
                    System.out.println("添加新註解");
                    aClass.addBefore(docComment, aClass.getModifierList());
                }

                System.out.println("修復成功應用");
            } catch (Exception e) {
                System.out.println("應用修復時出錯: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}