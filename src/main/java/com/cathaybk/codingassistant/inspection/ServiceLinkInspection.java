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
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

                // 如果本身有電文代號，也應該提示同步到其他相關類
                if (controllerApiIds.isEmpty() && hasApiIdFormat) {
                    String currentApiId = extractApiIdFromDoc(docComment.getText());
                    if (currentApiId != null) {
                        System.out.println("從當前類獲取電文代號: " + currentApiId);
                        controllerApiIds.put(className, currentApiId);
                    }
                }

                System.out.println("找到關聯的Controller API IDs: " + controllerApiIds.size() + " 個");
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
     * 查找服務類對應的 Controller 電文代號
     * 
     * @param aClass Service 類
     * @return 電文代號和描述的映射
     */
    private Map<String, String> findControllerApiIds(PsiClass aClass) {
        Map<String, String> result = new HashMap<>();
        String className = aClass.getName();
        if (className == null)
            return result;

        System.out.println("尋找 " + className + " 相關的 Controller 電文代號");

        try {
            // 1. 先檢查自身是否有 API ID
            PsiDocComment docComment = aClass.getDocComment();
            if (docComment != null) {
                String docText = docComment.getText();
                Matcher matcher = API_ID_PATTERN.matcher(docText);
                if (matcher.find()) {
                    String apiId = matcher.group(1);
                    System.out.println("  類自身有電文代號: " + apiId);
                    result.put(className, apiId);
                    return result;
                }
            }

            // 2. 如果這是一個實現類，檢查它實現的接口是否有 API ID
            if (className.contains("Impl")) {
                for (PsiClassType interfaceType : aClass.getImplementsListTypes()) {
                    PsiClass interfaceClass = interfaceType.resolve();
                    if (interfaceClass != null) {
                        PsiDocComment interfaceDoc = interfaceClass.getDocComment();
                        if (interfaceDoc != null) {
                            String interfaceDocText = interfaceDoc.getText();
                            Matcher matcher = API_ID_PATTERN.matcher(interfaceDocText);
                            if (matcher.find()) {
                                String apiId = matcher.group(1);
                                System.out.println("  實現的接口有電文代號: " + apiId);
                                result.put(interfaceClass.getName(), apiId);
                                return result;
                            }
                        }
                    }
                }
            }

            // 4. 查找引用 Service 的 Controller 方法
            Collection<PsiReference> references = ReferencesSearch
                    .search(aClass, GlobalSearchScope.projectScope(aClass.getProject())).findAll();
            System.out.println("  找到 " + references.size() + " 個引用");

            // 首先優先查找方法級別的引用和API ID
            boolean foundMethodLevelApiId = false;
            for (PsiReference reference : references) {
                System.out.println("  檢查引用: " + reference.getElement().getText());

                PsiMethod containingMethod = PsiTreeUtil.getParentOfType(reference.getElement(), PsiMethod.class);
                if (containingMethod != null) {
                    System.out.println("  引用在方法: " + containingMethod.getName() + " 中");

                    PsiClass containingClass = containingMethod.getContainingClass();
                    if (containingClass != null && containingClass.getName() != null) {
                        String containingClassName = containingClass.getName();
                        System.out.println("  方法所屬類: " + containingClassName);

                        if (containingClassName.contains("Controller")) {
                            // 檢查方法是否有API映射註解
                            boolean isApiMethod = false;
                            for (PsiAnnotation annotation : containingMethod.getModifierList().getAnnotations()) {
                                String annotationName = annotation.getQualifiedName();
                                if (annotationName != null &&
                                        (annotationName.endsWith("Mapping") ||
                                                annotationName.contains("RequestMapping") ||
                                                annotationName.contains("GetMapping") ||
                                                annotationName.contains("PostMapping"))) {
                                    System.out.println("  發現API方法: " + containingMethod.getName());
                                    isApiMethod = true;
                                    break;
                                }
                            }

                            if (!isApiMethod) {
                                System.out.println("  方法不是API方法，跳過: " + containingMethod.getName());
                                continue;
                            }

                            PsiDocComment methodDoc = containingMethod.getDocComment();
                            if (methodDoc != null) {
                                String methodDocText = methodDoc.getText();
                                Matcher matcher = API_ID_PATTERN.matcher(methodDocText);
                                if (matcher.find()) {
                                    String apiId = matcher.group(1);
                                    System.out.println("  找到 Controller 方法的電文代號: " + apiId);
                                    result.put(containingMethod.getName(), apiId);
                                    foundMethodLevelApiId = true;
                                }
                            }
                        }
                    }
                }
            }

            // 3. 如果沒有找到方法級別的API ID，嘗試從類名推導相關的 Controller 名稱
            if (!foundMethodLevelApiId) {
                String controllerName;
                if (className.contains("Service")) {
                    if (className.contains("Impl")) {
                        // 從 ServiceImpl 去掉 "Impl" 和 "Service" 後加上 "Controller"
                        controllerName = className.replace("ServiceImpl", "Controller");
                        controllerName = controllerName.replace("Service", "");
                    } else {
                        // 從 Service 去掉 "Service" 後加上 "Controller"
                        controllerName = className.replace("Service", "Controller");
                    }
                } else {
                    // 如果不含 "Service"，直接加上 "Controller"
                    controllerName = className + "Controller";
                }

                System.out.println("  推導可能的 Controller 名稱: " + controllerName);

                // 在 Controller 層找相應的方法並獲取電文代號
                Collection<PsiClass> controllers = findClassesByName(aClass.getProject(), controllerName);
                if (controllers.isEmpty()) {
                    System.out.println("  沒有找到名為 " + controllerName + " 的 Controller 類");

                    // 嘗試查找所有 Controller 類
                    controllers = findAllControllers(aClass.getProject());
                    System.out.println("  找到 " + controllers.size() + " 個 Controller 類");

                    if (controllers.isEmpty()) {
                        return result;
                    }
                }

                // 5. 對於每個 Controller，檢查其中調用 Service 的方法
                if (result.isEmpty()) {
                    System.out.println("  通過引用未找到電文代號，嘗試檢查所有 Controller");
                    for (PsiClass controller : controllers) {
                        System.out.println("  檢查 Controller: " + controller.getName());

                        // 檢查方法
                        for (PsiMethod method : controller.getMethods()) {
                            System.out.println("  檢查方法: " + method.getName());

                            // 檢查方法是否有API映射註解
                            boolean isApiMethod = false;
                            for (PsiAnnotation annotation : method.getModifierList().getAnnotations()) {
                                String annotationName = annotation.getQualifiedName();
                                if (annotationName != null &&
                                        (annotationName.endsWith("Mapping") ||
                                                annotationName.contains("RequestMapping") ||
                                                annotationName.contains("GetMapping") ||
                                                annotationName.contains("PostMapping"))) {
                                    System.out.println("  發現API方法: " + method.getName());
                                    isApiMethod = true;
                                    break;
                                }
                            }

                            if (!isApiMethod) {
                                System.out.println("  方法不是API方法，跳過: " + method.getName());
                                continue;
                            }

                            // 檢查方法文檔
                            PsiDocComment methodDoc = method.getDocComment();
                            if (methodDoc != null) {
                                String methodDocText = methodDoc.getText();
                                Matcher matcher = API_ID_PATTERN.matcher(methodDocText);
                                if (matcher.find()) {
                                    String apiId = matcher.group(1);
                                    System.out.println("  方法文檔有電文代號: " + apiId);

                                    // 檢查方法體中是否引用了 Service
                                    boolean usesService = checkMethodUsesService(method, aClass);
                                    if (usesService) {
                                        System.out.println("  方法使用了該 Service");
                                        result.put(method.getName(), apiId);
                                        break;
                                    }
                                }
                            }

                            // 檢查方法體中是否引用了 Service，即使沒有 API ID
                            boolean usesService = checkMethodUsesService(method, aClass);
                            if (usesService) {
                                System.out.println("  方法使用了該 Service，但沒有 API ID");
                            }
                        }

                        if (!result.isEmpty()) {
                            break;
                        }
                    }
                }

                // 6. 如果仍然找不到，嘗試檢查類級別的文檔（最後的選擇）
                if (result.isEmpty()) {
                    for (PsiClass controller : controllers) {
                        // 檢查類級別的文檔
                        PsiDocComment classDoc = controller.getDocComment();
                        if (classDoc != null) {
                            String classDocText = classDoc.getText();
                            Matcher matcher = API_ID_PATTERN.matcher(classDocText);
                            if (matcher.find()) {
                                String apiId = matcher.group(1);
                                System.out.println("  Controller 類文檔有電文代號: " + apiId);
                                result.put(controller.getName(), apiId);
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("尋找 Controller 電文代號時出錯: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("找到 " + result.size() + " 個關聯的 Controller API IDs");
        return result;
    }

    /**
     * 查找項目中所有的 Controller 類
     */
    private Collection<PsiClass> findAllControllers(Project project) {
        Collection<PsiClass> result = new ArrayList<>();

        try {
            // 查找名字包含 "Controller" 的類
            PsiClass[] controllers = JavaPsiFacade.getInstance(project).findClasses(
                    "*Controller", GlobalSearchScope.projectScope(project));

            for (PsiClass controller : controllers) {
                result.add(controller);
            }

            // 查找在 controller 包下的類
            PsiClass[] controllerPackageClasses = JavaPsiFacade.getInstance(project).findClasses(
                    "*.controller.*", GlobalSearchScope.projectScope(project));

            for (PsiClass cls : controllerPackageClasses) {
                if (!result.contains(cls)) {
                    result.add(cls);
                }
            }

            System.out.println("找到 " + result.size() + " 個controller包下的類");
        } catch (Exception e) {
            System.out.println("查找 Controller 類時出錯: " + e.getMessage());
        }

        return result;
    }

    /**
     * 根據名稱查找類
     */
    private Collection<PsiClass> findClassesByName(Project project, String className) {
        Collection<PsiClass> result = new ArrayList<>();

        try {
            PsiClass[] classes = JavaPsiFacade.getInstance(project).findClasses(
                    className, GlobalSearchScope.projectScope(project));

            Collections.addAll(result, classes);

            // 如果沒有精確匹配，嘗試模糊匹配
            if (result.isEmpty()) {
                PsiClass[] fuzzyClasses = JavaPsiFacade.getInstance(project).findClasses(
                        "*" + className + "*", GlobalSearchScope.projectScope(project));

                for (PsiClass cls : fuzzyClasses) {
                    if (cls.getName() != null && cls.getName().contains(className)) {
                        result.add(cls);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("查找類時出錯: " + e.getMessage());
        }

        return result;
    }

    /**
     * 檢查方法是否使用了指定的 Service 類
     */
    private boolean checkMethodUsesService(PsiMethod method, PsiClass serviceClass) {
        if (method.getBody() == null)
            return false;

        // 檢查方法參數
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            PsiType type = parameter.getType();
            PsiClass parameterClass = PsiUtil.resolveClassInType(type);
            if (parameterClass != null && parameterClass.equals(serviceClass)) {
                return true;
            }
        }

        // 創建訪問器，檢查方法體中的引用
        final boolean[] usesService = { false };

        method.getBody().accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitReferenceExpression(PsiReferenceExpression expression) {
                super.visitReferenceExpression(expression);

                if (usesService[0])
                    return; // 已經找到引用，不再繼續

                PsiElement resolved = expression.resolve();
                if (resolved instanceof PsiField) {
                    PsiField field = (PsiField) resolved;
                    PsiClass fieldType = PsiUtil.resolveClassInType(field.getType());
                    if (fieldType != null && (fieldType.equals(serviceClass) ||
                            (serviceClass.isInterface() && isImplementationOf(fieldType, serviceClass)))) {
                        usesService[0] = true;
                    }
                } else if (resolved instanceof PsiMethod) {
                    PsiMethod resolvedMethod = (PsiMethod) resolved;
                    PsiClass containingClass = resolvedMethod.getContainingClass();
                    if (containingClass != null && (containingClass.equals(serviceClass) ||
                            (serviceClass.isInterface() && isImplementationOf(containingClass, serviceClass)))) {
                        usesService[0] = true;
                    }
                }
            }
        });

        return usesService[0];
    }

    /**
     * 判斷一個類是否是指定接口的實現類
     */
    private boolean isImplementationOf(PsiClass cls, PsiClass interfaceClass) {
        if (!interfaceClass.isInterface() || cls.isInterface()) {
            return false;
        }

        for (PsiClassType implementsType : cls.getImplementsListTypes()) {
            PsiClass implemented = implementsType.resolve();
            if (implemented != null && (implemented.equals(interfaceClass) ||
                    isImplementationOf(implemented, interfaceClass))) {
                return true;
            }
        }

        // 檢查父類
        PsiClass superClass = cls.getSuperClass();
        return superClass != null && isImplementationOf(superClass, interfaceClass);
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

    /**
     * 從Javadoc文字中提取電文代號
     */
    private String extractApiIdFromDoc(String docText) {
        Matcher matcher = API_ID_PATTERN.matcher(docText);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}