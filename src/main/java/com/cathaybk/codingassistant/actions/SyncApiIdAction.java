package com.cathaybk.codingassistant.actions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 同步 API 電文代號到相關的 Service 和 ServiceImpl 的快速修復
 */
public class SyncApiIdAction implements IntentionAction, LocalQuickFix {
    private static final Logger LOG = Logger.getInstance(SyncApiIdAction.class);

    // 定義電文代號的正則表達式模式
    private static final Pattern API_ID_PATTERN = Pattern.compile("([A-Za-z0-9]+-[A-Za-z0-9]+-[A-Za-z0-9]+.*)");

    @NotNull
    @Override
    public String getText() {
        return "同步電文代號到所有相關類";
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "同步電文代號";
    }

    @NotNull
    @Override
    public String getName() {
        return getFamilyName();
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        if (!(file instanceof PsiJavaFile)) {
            return false;
        }

        // 使用原子變量來存儲結果
        final boolean[] result = { false };

        // 使用runReadAction確保讀取PSI在正確的線程上
        ApplicationManager.getApplication().runReadAction(() -> {
            int offset = editor.getCaretModel().getOffset();
            PsiElement element = file.findElementAt(offset);
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);

            if (method != null) {
                result[0] = hasApiIdJavadoc(method);
                return;
            }

            // 如果不是方法上，檢查是否在類上
            PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            result[0] = psiClass != null && hasApiIdJavadoc(psiClass);
        });

        return result[0];
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);

        // 使用讀操作來識別要處理的元素
        ApplicationManager.getApplication().runReadAction(() -> {
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (method != null) {
                syncApiId(project, method);
            } else {
                // 檢查是否在類上
                PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                if (psiClass != null && hasApiIdJavadoc(psiClass)) {
                    syncApiIdForClass(project, psiClass);
                }
            }
        });
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();

        // 使用讀操作來識別要處理的元素
        ApplicationManager.getApplication().runReadAction(() -> {
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (method != null) {
                syncApiId(project, method);
            }
        });
    }

    // 根據 IDE 版本，ActionUpdateThread 可能不存在於舊版 IntentionAction 介面中
    // 移除 @Override 以避免編譯錯誤
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public boolean startInWriteAction() {
        return false; // 我們現在手動管理寫入操作，而不是依賴框架自動開始寫入
    }

    /**
     * 同步方法上的 API 電文代號
     * (處理 Controller -> Service/Impl 類，Service/Impl -> Controller 方法)
     */
    private void syncApiId(Project project, PsiMethod sourceMethod) {
        String apiId = extractApiIdFromElement(sourceMethod);
        if (apiId == null) {
            /* ... error handling ... */ return;
        }
        final String finalApiId = apiId;

        // 使用runReadAction包裝所有讀取操作
        ApplicationManager.getApplication().runReadAction(() -> {
            PsiClass sourceClass = sourceMethod.getContainingClass();
            if (sourceClass == null) {
                /* ... error handling ... */ return;
            }

            Map<PsiClass, List<PsiMethod>> targetMethodMap = new HashMap<>();
            Set<PsiClass> targetClassSet = new HashSet<>();
            boolean updateTargetMethods;
            String noTargetsMessage = "";

            // --- 判斷來源並查找目標 ---
            if (isControllerMethod(sourceMethod)) {
                updateTargetMethods = false;
                LOG.info("來源: Controller 方法 " + sourceClass.getName() + "." + sourceMethod.getName()
                        + " --> 目標: Service/Impl 類");
                findRelatedServiceClassesOnly(project, sourceMethod, targetClassSet);
                if (targetClassSet.isEmpty())
                    noTargetsMessage = "在方法 " + sourceMethod.getName() + " 中未找到使用的 Service 類。";
            } else if (sourceClass.getName() != null && sourceClass.getName().contains("Service")) {
                updateTargetMethods = true;
                LOG.info("來源: Service/Impl 方法 " + sourceClass.getName() + "." + sourceMethod.getName()
                        + " --> 目標: Controller 方法");
                findRelatedControllerMethods(project, sourceMethod, targetMethodMap);
                if (targetMethodMap.isEmpty())
                    noTargetsMessage = "未找到調用 Service 方法 " + sourceMethod.getName() + " 的 Controller 方法。";
            } else {
                LOG.warn("無法識別的來源方法類型: " + sourceClass.getName() + "." + sourceMethod.getName());
                showInfoMessage("無法識別的來源方法類型。", "同步失敗"); // 這個可以直接顯示，因為是終端情況
                return;
            }

            // 如果找不到目標，提前顯示訊息並返回
            if (!noTargetsMessage.isEmpty()) {
                // 使用 invokeLater 顯示，避免 side effect
                final String finalMsg = noTargetsMessage;
                ApplicationManager.getApplication().invokeLater(() -> showInfoMessage(finalMsg, "同步提醒"));
                return;
            }

            // 使用invokeLater確保在UI線程執行寫入操作
            ApplicationManager.getApplication().invokeLater(() -> {
                // --- 執行 PSI 修改 ---
                final StringBuilder resultMsg = new StringBuilder();
                final AtomicInteger count = new AtomicInteger(0); // 使用 AtomicInteger

                // WriteCommandAction 處理 PSI 修改和線程
                WriteCommandAction.runWriteCommandAction(project, "更新API電文代號", null, () -> {
                    try {
                        if (updateTargetMethods) {
                            LOG.info("執行更新目標【方法】的註解...");
                            count.set(updateApiIdToTargets(project, targetMethodMap, finalApiId, resultMsg));
                        } else {
                            LOG.info("執行更新目標【類】的註解...");
                            List<PsiClass> targetClassList = new ArrayList<>(targetClassSet);
                            targetClassList.remove(sourceClass);
                            if (!targetClassList.isEmpty()) {
                                count.set(updateApiIdToClasses(project, targetClassList, finalApiId, resultMsg));
                            } else {
                                LOG.info("目標類列表為空或只包含來源類，無需更新。");
                            }
                        }
                    } catch (Exception e) {
                        LOG.error("更新電文代號時出錯", e);
                        resultMsg.append("\n錯誤: ").append(e.getMessage()); // 記錄錯誤信息
                    }
                }); // End WriteCommandAction

                // --- 在操作完成後，顯示最終結果 ---
                String messageToShow;
                String titleToShow;
                if (resultMsg.toString().contains("錯誤:")) {
                    messageToShow = "更新電文代號時出錯: " + resultMsg.toString();
                    titleToShow = "同步失敗";
                    showErrorMessage(messageToShow, titleToShow);
                } else if (count.get() > 0) {
                    messageToShow = "已成功將電文代號 '" + finalApiId + "' 同步到 " + count.get() + " 個相關目標:\n" + resultMsg;
                    titleToShow = "同步成功";
                    showInfoMessage(messageToShow, titleToShow);
                } else {
                    messageToShow = "沒有目標需要更新電文代號 (可能已存在相同註解或未找到目標)。";
                    titleToShow = "同步結果";
                    showInfoMessage(messageToShow, titleToShow);
                }
            });
        });
    }

    /**
     * 同步類上的 API 電文代號
     * (處理 Service/Impl 類 -> Service/Impl 類 & Controller 方法)
     */
    private void syncApiIdForClass(Project project, PsiClass sourceClass) {
        String apiId = extractApiIdFromElement(sourceClass);
        if (apiId == null) {
            /* ... error handling ... */ return;
        }
        final String finalApiId = apiId;
        LOG.info("從類 " + sourceClass.getName() + " 提取到電文代號: " + apiId);

        // 在寫入操作外執行讀取操作，先收集所有必要的資料
        ApplicationManager.getApplication().runReadAction(() -> {
            // --- 查找並分類目標 ---
            List<PsiClass> relatedClasses = findRelatedClasses(project, sourceClass);
            final List<PsiClass> allRelatedClasses = getAllRelatedClassesWithImpls(project, relatedClasses);

            List<PsiClass> controllerClasses = new ArrayList<>();
            List<PsiClass> otherTargetClasses = new ArrayList<>();
            boolean hasTargets = false;
            for (PsiClass relatedClass : allRelatedClasses) {
                if (relatedClass.equals(sourceClass))
                    continue; // Skip source
                hasTargets = true; // 只要有不是 source 的相關類，就認為有目標
                if (isControllerClass(relatedClass)) {
                    if (!controllerClasses.contains(relatedClass))
                        controllerClasses.add(relatedClass);
                } else {
                    if (!otherTargetClasses.contains(relatedClass))
                        otherTargetClasses.add(relatedClass);
                }
            }

            if (!hasTargets) {
                final String msg = "找不到與 " + sourceClass.getName() + " 相關的其他類";
                ApplicationManager.getApplication().invokeLater(() -> showInfoMessage(msg, "同步提醒"));
                return;
            }

            // 使用invokeLater確保在UI線程執行寫入操作
            ApplicationManager.getApplication().invokeLater(() -> {
                // --- 執行 PSI 修改 ---
                final StringBuilder resultMsg = new StringBuilder();
                final AtomicInteger totalCount = new AtomicInteger(0);

                WriteCommandAction.runWriteCommandAction(project, "更新API電文代號", null, () -> {
                    try {
                        int currentCount = 0;
                        // 更新非 Controller 類
                        if (!otherTargetClasses.isEmpty()) {
                            LOG.info("執行更新 " + otherTargetClasses.size() + " 個 Service/Impl 【類】的註解...");
                            currentCount += updateApiIdToClasses(project, otherTargetClasses, finalApiId, resultMsg);
                        }
                        // 更新 Controller 方法
                        if (!controllerClasses.isEmpty()) {
                            LOG.info("執行更新 " + controllerClasses.size() + " 個 Controller 【方法】的註解...");
                            currentCount += updateApiIdToControllerMethods(project, controllerClasses, sourceClass,
                                    finalApiId, resultMsg);
                        }
                        totalCount.set(currentCount);
                    } catch (Exception e) {
                        LOG.error("更新電文代號時出錯", e);
                        resultMsg.append("\n錯誤: ").append(e.getMessage());
                    }
                }); // End WriteCommandAction

                // --- 在操作完成後，顯示最終結果 ---
                String messageToShow;
                String titleToShow;
                if (resultMsg.toString().contains("錯誤:")) {
                    messageToShow = "更新電文代號時出錯: " + resultMsg.toString();
                    titleToShow = "同步失敗";
                    showErrorMessage(messageToShow, titleToShow);
                } else if (totalCount.get() > 0) {
                    messageToShow = "已成功將電文代號 '" + finalApiId + "' 同步到 " + totalCount.get() + " 個相關目標:\n" + resultMsg;
                    titleToShow = "同步成功";
                    showInfoMessage(messageToShow, titleToShow);
                } else {
                    messageToShow = "沒有找到需要更新電文代號的類或方法。";
                    titleToShow = "同步結果";
                    showInfoMessage(messageToShow, titleToShow);
                }
            });
        });
    }

    /**
     * 更新電文代號到目標 Controller 類中的相關方法。
     *
     * @param project            當前專案
     * @param controllerClasses  目標 Controller 類列表
     * @param sourceServiceClass 觸發同步的來源 Service 接口或實現類
     * @param apiId              要同步的電文代號
     * @param resultMsg          用於記錄結果訊息的 StringBuilder
     * @return 更新的方法數量
     */
    private int updateApiIdToControllerMethods(Project project, List<PsiClass> controllerClasses,
            PsiClass sourceServiceClass, String apiId, StringBuilder resultMsg) {
        LOG.info("正在為 " + controllerClasses.size() + " 個 Controller 查找使用了 " + sourceServiceClass.getName() + " 的方法...");
        Map<PsiClass, List<PsiMethod>> targetMethodMap = new HashMap<>();
        int relatedMethodCount = 0;

        // 遍歷所有目標 Controller 類
        for (PsiClass controllerClass : controllerClasses) {
            LOG.debug("檢查 Controller: " + controllerClass.getName());
            List<PsiMethod> relatedMethodsInThisController = new ArrayList<>();
            // 遍歷 Controller 的所有方法
            for (PsiMethod controllerMethod : controllerClass.getMethods()) {
                // 只處理非構造函數的公開方法 (可選，根據需要調整)
                if (!controllerMethod.isConstructor()
                        && controllerMethod.getModifierList().hasModifierProperty(PsiModifier.PUBLIC)) {
                    LOG.trace("  檢查方法: " + controllerMethod.getName());
                    // 判斷此 Controller 方法是否與來源 Service 相關
                    if (isControllerMethodRelatedToService(controllerMethod, sourceServiceClass)) {
                        LOG.debug("  找到相關方法: " + controllerClass.getName() + "." + controllerMethod.getName());
                        relatedMethodsInThisController.add(controllerMethod);
                        relatedMethodCount++;
                    }
                }
            }
            // 如果在這個 Controller 中找到了相關方法，則添加到 Map 中
            if (!relatedMethodsInThisController.isEmpty()) {
                targetMethodMap.put(controllerClass, relatedMethodsInThisController);
            } else {
                LOG.info("在 Controller " + controllerClass.getName() + " 中未找到使用 " + sourceServiceClass.getName()
                        + " 的相關方法。");
            }
        }

        // 如果找到了相關的 Controller 方法，則更新它們的註解
        if (!targetMethodMap.isEmpty()) {
            LOG.info("共找到 " + relatedMethodCount + " 個相關的 Controller 方法，準備更新註解...");
            // 複用 updateApiIdToTargets 的邏輯來更新方法註解
            // 注意：updateApiIdToTargets 返回的是成功更新的數量，可能因已存在相同註解而小於 relatedMethodCount
            return updateApiIdToTargets(project, targetMethodMap, apiId, resultMsg);
        } else {
            String controllerNames = controllerClasses.stream().map(PsiClass::getName)
                    .collect(Collectors.joining(", "));
            LOG.info("在所有檢查的 Controller (" + controllerNames + ") 中均未找到使用 " + sourceServiceClass.getName() + " 的相關方法。");
            resultMsg.append("- 在 Controller 類 [").append(controllerNames).append("] 中未找到與 ")
                    .append(sourceServiceClass.getName()).append(" 相關聯的方法。\n");
            return 0; // 沒有方法被更新
        }
    }

    /**
     * 判斷一個 Controller 方法是否與指定的 Service 類 (接口或實現) 相關。
     * 檢查方法體內是否有對 Service 實例的方法調用。
     *
     * @param controllerMethod   要檢查的 Controller 方法
     * @param sourceServiceClass 來源 Service 接口或實現類
     * @return 如果相關則返回 true，否則 false
     */
    private boolean isControllerMethodRelatedToService(PsiMethod controllerMethod, PsiClass sourceServiceClass) {
        PsiCodeBlock body = controllerMethod.getBody();
        if (body == null) {
            return false; // 沒有方法體，無法判斷
        }
        PsiClass controllerClass = controllerMethod.getContainingClass();
        if (controllerClass == null) {
            return false;
        }

        // 建立一個包含來源 Service 類及其實現/接口的集合，方便後續比較
        final Set<PsiClass> relevantServiceClasses = new HashSet<>();
        relevantServiceClasses.add(sourceServiceClass);
        // 如果來源是實現類，添加它實現的接口
        if (!sourceServiceClass.isInterface()) {
            for (PsiClassType interfaceType : sourceServiceClass.getImplementsListTypes()) {
                PsiClass interfaceClass = interfaceType.resolve();
                if (interfaceClass != null) {
                    relevantServiceClasses.add(interfaceClass);
                }
            }
        }
        // 如果來源是接口，找到它的實現類 (可選，看是否需要反向關聯)
        // Collection<PsiClass> impls =
        // findImplementingClasses(controllerMethod.getProject(), sourceServiceClass);
        // relevantServiceClasses.addAll(impls);

        // LOG.trace(" 相關 Service 判斷集合 ("+controllerMethod.getName()+"): " +
        // relevantServiceClasses.stream().map(PsiClass::getName).collect(Collectors.joining(",
        // ")));

        // 查找 Controller 中注入的相關 Service 字段
        final List<PsiField> relevantServiceFields = new ArrayList<>();
        for (PsiField field : controllerClass.getFields()) {
            PsiType fieldType = field.getType();
            PsiClass fieldClass = PsiUtil.resolveClassInType(fieldType);
            if (fieldClass != null && relevantServiceClasses.contains(fieldClass)) {
                relevantServiceFields.add(field);
                LOG.trace("    找到相關 Service 字段: " + field.getName() + " (" + fieldClass.getName() + ")");
            }
        }

        // 使用 Visitor 遍歷方法體，查找方法調用
        final boolean[] isRelated = { false }; // 用數組讓內部類可以修改
        body.accept(new JavaRecursiveElementWalkingVisitor() {

            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression); // 繼續遍歷子節點

                // 如果已經找到關聯，就不再繼續檢查
                if (isRelated[0]) {
                    return;
                }

                // 嘗試解析被調用的方法
                PsiMethod calledMethod = expression.resolveMethod();
                if (calledMethod != null) {
                    PsiClass containingClass = calledMethod.getContainingClass();
                    // LOG.trace(" 訪問方法調用: " + (containingClass != null ? containingClass.getName()
                    // : "null") + "." + calledMethod.getName());

                    // 檢查被調用方法所屬的類是否是我們關心的 Service 類 (或其接口/實現)
                    if (containingClass != null && relevantServiceClasses.contains(containingClass)) {
                        LOG.trace("      直接調用了相關 Service 方法: " + containingClass.getName() + "."
                                + calledMethod.getName());
                        isRelated[0] = true;
                        return; // 找到關聯，停止當前分支的遍歷
                    }
                }

                // 檢查調用是否通過注入的 Service 字段發起
                PsiReferenceExpression methodExpression = expression.getMethodExpression();
                PsiExpression qualifier = methodExpression.getQualifierExpression(); // 獲取調用者表達式，例如
                                                                                     // serviceField.callMethod() 中的
                                                                                     // serviceField

                if (qualifier instanceof PsiReferenceExpression) {
                    PsiElement resolvedQualifier = ((PsiReferenceExpression) qualifier).resolve();
                    // LOG.trace(" 調用者解析結果: " + (resolvedQualifier != null ?
                    // resolvedQualifier.toString() : "null"));
                    // 檢查調用者是否是我們之前找到的相關 Service 字段之一
                    if (resolvedQualifier instanceof PsiField && relevantServiceFields.contains(resolvedQualifier)) {
                        LOG.trace("      通過相關 Service 字段 " + ((PsiField) resolvedQualifier).getName() + " 調用了方法");
                        isRelated[0] = true;
                        // return; // 找到關聯
                    }
                }
            }

            // 可以選擇性地覆蓋其他 visit 方法，例如 visitReferenceExpression 來查找對 Service 字段的直接引用，
            // 但僅檢查方法調用通常足夠判斷 "使用" 關係。
        });

        // LOG.trace(" 方法 " + controllerMethod.getName() + " 關聯性判斷結果: " + isRelated[0]);
        return isRelated[0];
    }

    /**
     * 更新電文代號到目標方法
     */
    private int updateApiIdToTargets(Project project, Map<PsiClass, List<PsiMethod>> targetMap, String apiId,
            StringBuilder resultMsg) {
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        int count = 0;

        for (Map.Entry<PsiClass, List<PsiMethod>> entry : targetMap.entrySet()) {
            PsiClass psiClass = entry.getKey();
            List<PsiMethod> methods = entry.getValue();

            for (PsiMethod method : methods) {
                // 檢查是否已有相同的電文代號
                boolean alreadyHasSameApiId = false;
                PsiDocComment existingComment = method.getDocComment();

                if (existingComment != null) {
                    String existingApiId = extractApiIdFromDocComment(existingComment);
                    if (existingApiId != null) {
                        String existingMainPart = extractMainPartOfApiId(existingApiId);
                        String apiIdMainPart = extractMainPartOfApiId(apiId);
                        alreadyHasSameApiId = apiIdMainPart.equals(existingMainPart) && apiId.equals(existingApiId);
                    }
                }

                if (alreadyHasSameApiId) {
                    resultMsg.append("- ").append(psiClass.getName()).append(".").append(method.getName())
                            .append(" (已有相同電文代號)\n");
                    continue;
                }

                // 創建新的 Javadoc 註解
                final PsiDocComment newDocComment = factory.createDocCommentFromText(
                        "/**\n * " + apiId + "\n */");

                try {
                    if (existingComment != null) {
                        existingComment.replace(newDocComment);
                    } else {
                        method.addBefore(newDocComment, method.getModifierList());
                    }

                    count++;
                    resultMsg.append("- ").append(psiClass.getName()).append(".").append(method.getName())
                            .append(existingComment != null ? " (更新)" : " (新增)").append("\n");
                } catch (Exception e) {
                    LOG.error("更新方法文檔註解失敗", e);
                    resultMsg.append("- ").append(psiClass.getName()).append(".").append(method.getName())
                            .append(" (更新失敗: ").append(e.getMessage()).append(")\n");
                }
            }
        }

        return count;
    }

    /**
     * 更新電文代號到目標類
     */
    private int updateApiIdToClasses(Project project, List<PsiClass> classes, String apiId, StringBuilder resultMsg) {
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        int count = 0;

        LOG.info("準備更新 " + classes.size() + " 個類的電文代號: " + apiId);

        for (PsiClass psiClass : classes) {
            // 檢查是否已有相同的電文代號
            boolean alreadyHasSameApiId = false;
            PsiDocComment existingComment = psiClass.getDocComment();

            if (existingComment != null) {
                String existingApiId = extractApiIdFromDocComment(existingComment);
                if (existingApiId != null) {
                    String existingMainPart = extractMainPartOfApiId(existingApiId);
                    String apiIdMainPart = extractMainPartOfApiId(apiId);
                    alreadyHasSameApiId = apiIdMainPart.equals(existingMainPart) && apiId.equals(existingApiId);
                }
            }

            if (alreadyHasSameApiId) {
                resultMsg.append("- ").append(psiClass.getName()).append(" (已有相同電文代號)\n");
                continue;
            }

            // 創建新的 Javadoc 註解
            final PsiDocComment newDocComment = factory.createDocCommentFromText(
                    "/**\n * " + apiId + "\n */");

            try {
                if (existingComment != null) {
                    existingComment.replace(newDocComment);
                } else {
                    psiClass.addBefore(newDocComment, psiClass.getModifierList());
                }

                count++;
                resultMsg.append("- ").append(psiClass.getName())
                        .append(existingComment != null ? " (更新)" : " (新增)").append("\n");
            } catch (Exception e) {
                LOG.error("更新類文檔註解失敗", e);
                resultMsg.append("- ").append(psiClass.getName()).append(" (更新失敗: ").append(e.getMessage())
                        .append(")\n");
            }
        }

        return count;
    }

    /**
     * 從 PsiElement 提取電文代號
     */
    private String extractApiIdFromElement(PsiDocCommentOwner element) {
        PsiDocComment docComment = element.getDocComment();
        if (docComment != null) {
            return extractApiIdFromDocComment(docComment);
        }
        return null;
    }

    /**
     * 從文檔註解中提取電文代號
     */
    private String extractApiIdFromDocComment(PsiDocComment docComment) {
        if (docComment == null) {
            return null;
        }

        String docText = docComment.getText();
        Matcher matcher = API_ID_PATTERN.matcher(docText);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 判斷元素是否有電文代號 Javadoc
     */
    private boolean hasApiIdJavadoc(PsiDocCommentOwner element) {
        return extractApiIdFromElement(element) != null;
    }

    /**
     * 判斷一個方法是否是 Controller 方法
     */
    private boolean isControllerMethod(PsiMethod method) {
        // 檢查方法是否有@PostMapping、@GetMapping等註解
        PsiAnnotation[] annotations = method.getModifierList().getAnnotations();
        for (PsiAnnotation annotation : annotations) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null && (qualifiedName.endsWith("Mapping") ||
                    qualifiedName.contains("RequestMapping") ||
                    qualifiedName.contains("PostMapping") ||
                    qualifiedName.contains("GetMapping"))) {
                return true;
            }
        }

        // 檢查是否在 Controller 類中
        PsiClass containingClass = method.getContainingClass();
        return containingClass != null &&
                containingClass.getName() != null &&
                (containingClass.getName().contains("Controller") ||
                        (containingClass.getQualifiedName() != null &&
                                containingClass.getQualifiedName().contains("controller")));
    }

    /**
     * 查找與 Controller 方法相關的 Service 類和方法
     */
    private void findRelatedServiceClasses(Project project, PsiMethod controllerMethod,
            Map<PsiClass, List<PsiMethod>> targetMap) {
        PsiClass containingClass = controllerMethod.getContainingClass();
        if (containingClass == null) {
            return;
        }

        // 從方法體中收集使用的 Service 類及其方法
        PsiCodeBlock body = controllerMethod.getBody();
        if (body != null) {
            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    super.visitMethodCallExpression(expression);

                    // 解析方法調用
                    PsiMethod calledMethod = expression.resolveMethod();
                    if (calledMethod == null)
                        return;

                    PsiClass calledClass = calledMethod.getContainingClass();
                    if (calledClass == null)
                        return;

                    // 檢查是否是 Service 類
                    if (isServiceClass(calledClass)) {
                        targetMap.computeIfAbsent(calledClass, k -> new ArrayList<>()).add(calledMethod);

                        // 如果是接口，也添加其實現類中的對應方法
                        if (calledClass.isInterface()) {
                            findServiceImplMethods(project, calledClass, calledMethod, targetMap);
                        }
                    }
                }

                @Override
                public void visitReferenceExpression(PsiReferenceExpression expression) {
                    super.visitReferenceExpression(expression);

                    PsiElement resolved = expression.resolve();
                    if (resolved instanceof PsiField) {
                        PsiField field = (PsiField) resolved;
                        PsiClass fieldType = PsiUtil.resolveClassInType(field.getType());

                        if (fieldType != null && isServiceClass(fieldType)) {
                            // 對於字段引用，添加該 Service 類的所有公開方法
                            for (PsiMethod serviceMethod : fieldType.getMethods()) {
                                if (serviceMethod.getModifierList().hasModifierProperty(PsiModifier.PUBLIC)) {
                                    targetMap.computeIfAbsent(fieldType, k -> new ArrayList<>()).add(serviceMethod);
                                }
                            }

                            // 如果是接口，處理實現類
                            if (fieldType.isInterface()) {
                                Collection<PsiClass> impls = findImplementingClasses(project, fieldType);
                                for (PsiClass impl : impls) {
                                    for (PsiMethod implMethod : impl.getMethods()) {
                                        // 只處理公開且覆寫接口方法的方法
                                        if (implMethod.getModifierList().hasModifierProperty(PsiModifier.PUBLIC) &&
                                                isOverridingInterfaceMethod(implMethod, fieldType)) {
                                            targetMap.computeIfAbsent(impl, k -> new ArrayList<>()).add(implMethod);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    /**
     * 查找 Service 接口方法的實現類方法
     */
    private void findServiceImplMethods(Project project, PsiClass serviceInterface, PsiMethod interfaceMethod,
            Map<PsiClass, List<PsiMethod>> targetMap) {
        Collection<PsiClass> impls = findImplementingClasses(project, serviceInterface);

        for (PsiClass impl : impls) {
            for (PsiMethod implMethod : impl.getMethods()) {
                // 檢查是否是對接口方法的實現
                if (isImplementationOf(implMethod, interfaceMethod)) {
                    targetMap.computeIfAbsent(impl, k -> new ArrayList<>()).add(implMethod);
                }
            }
        }
    }

    /**
     * 查找與 Service 方法相關的 Controller 方法
     */
    private void findRelatedControllerMethods(Project project, PsiMethod serviceMethod,
            Map<PsiClass, List<PsiMethod>> targetMap) {
        PsiClass serviceClass = serviceMethod.getContainingClass();
        if (serviceClass == null)
            return;

        // 查找所有引用此 Service 方法的地方
        Collection<PsiReference> references = ReferencesSearch
                .search(serviceMethod, GlobalSearchScope.projectScope(project)).findAll();

        for (PsiReference reference : references) {
            PsiElement element = reference.getElement();

            // 尋找調用此方法的 Controller 方法
            PsiMethod callingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (callingMethod != null) {
                PsiClass callingClass = callingMethod.getContainingClass();
                if (callingClass != null && isControllerClass(callingClass)) {
                    targetMap.computeIfAbsent(callingClass, k -> new ArrayList<>()).add(callingMethod);
                }
            }
        }

        // 如果是實現類方法，也尋找對應接口方法的引用
        if (isServiceImplClass(serviceClass)) {
            for (PsiMethod interfaceMethod : findCorrespondingInterfaceMethods(serviceMethod)) {
                Collection<PsiReference> interfaceRefs = ReferencesSearch
                        .search(interfaceMethod, GlobalSearchScope.projectScope(project)).findAll();

                for (PsiReference ref : interfaceRefs) {
                    PsiElement element = ref.getElement();
                    PsiMethod callingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                    if (callingMethod != null) {
                        PsiClass callingClass = callingMethod.getContainingClass();
                        if (callingClass != null && isControllerClass(callingClass)) {
                            targetMap.computeIfAbsent(callingClass, k -> new ArrayList<>()).add(callingMethod);
                        }
                    }
                }
            }
        }
    }

    /**
     * 判斷方法是否實現了指定介面的方法
     */
    private boolean isImplementationOf(PsiMethod implMethod, PsiMethod interfaceMethod) {
        if (!implMethod.getName().equals(interfaceMethod.getName())) {
            return false;
        }

        // 檢查方法簽名是否匹配
        PsiParameter[] implParams = implMethod.getParameterList().getParameters();
        PsiParameter[] interfaceParams = interfaceMethod.getParameterList().getParameters();

        if (implParams.length != interfaceParams.length) {
            return false;
        }

        for (int i = 0; i < implParams.length; i++) {
            if (!implParams[i].getType().equals(interfaceParams[i].getType())) {
                return false;
            }
        }

        return true;
    }

    /**
     * 判斷方法是否覆寫了接口中的方法
     */
    private boolean isOverridingInterfaceMethod(PsiMethod method, PsiClass interfaceClass) {
        for (PsiMethod interfaceMethod : interfaceClass.getMethods()) {
            if (isImplementationOf(method, interfaceMethod)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 尋找實現類方法對應的接口方法
     */
    private List<PsiMethod> findCorrespondingInterfaceMethods(PsiMethod implMethod) {
        List<PsiMethod> result = new ArrayList<>();
        PsiClass implClass = implMethod.getContainingClass();
        if (implClass == null)
            return result;

        for (PsiClassType interfaceType : implClass.getImplementsListTypes()) {
            PsiClass interfaceClass = interfaceType.resolve();
            if (interfaceClass != null) {
                for (PsiMethod interfaceMethod : interfaceClass.getMethods()) {
                    if (isImplementationOf(implMethod, interfaceMethod)) {
                        result.add(interfaceMethod);
                    }
                }
            }
        }

        return result;
    }

    /**
     * 查找接口的所有實現類
     */
    private Collection<PsiClass> findImplementingClasses(Project project, PsiClass interfaceClass) {
        Collection<PsiClass> result = new ArrayList<>();
        if (!interfaceClass.isInterface()) {
            return result;
        }

        // 方法1：使用 ReferencesSearch 查找所有引用
        try {
            Collection<PsiReference> references = ReferencesSearch.search(interfaceClass,
                    GlobalSearchScope.projectScope(project)).findAll();

            for (PsiReference reference : references) {
                PsiElement element = reference.getElement();
                // 檢查是否是 "implements" 子句中的引用
                if (element.getParent() instanceof PsiJavaCodeReferenceElement) {
                    PsiJavaCodeReferenceElement refElement = (PsiJavaCodeReferenceElement) element.getParent();
                    if (refElement.getParent() instanceof PsiReferenceList) {
                        PsiReferenceList refList = (PsiReferenceList) refElement.getParent();
                        if (refList.getParent() instanceof PsiClass &&
                                refList.getFirstChild() != null &&
                                refList.getFirstChild().getText().equals("implements")) {

                            PsiClass implementingClass = (PsiClass) refList.getParent();
                            if (!result.contains(implementingClass)) {
                                result.add(implementingClass);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("搜索實現類引用時出錯", e);
        }

        // 方法2：按命名規則查找
        if (result.isEmpty()) {
            try {
                String implName = interfaceClass.getName() + "Impl";

                // 在項目範圍內查找
                PsiClass[] implClasses = JavaPsiFacade.getInstance(project)
                        .findClasses(implName, GlobalSearchScope.projectScope(project));

                for (PsiClass implClass : implClasses) {
                    // 驗證是否確實實現了接口
                    for (PsiClassType implementedType : implClass.getImplementsListTypes()) {
                        PsiClass implemented = implementedType.resolve();
                        if (implemented != null && implemented.equals(interfaceClass)) {
                            if (!result.contains(implClass)) {
                                result.add(implClass);
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("根據命名規則查找實現類時出錯", e);
            }
        }

        // 方法3：搜索可能的包路徑
        if (result.isEmpty()) {
            try {
                String qualifiedName = interfaceClass.getQualifiedName();
                if (qualifiedName != null) {
                    // 嘗試常見的包路徑規則
                    String packageName = qualifiedName.substring(0, qualifiedName.lastIndexOf('.') + 1);
                    String[] possibleImplPackages = {
                            packageName + "impl.",
                            packageName,
                            packageName.replace(".service.", ".service.impl.")
                    };

                    for (String implPackage : possibleImplPackages) {
                        String implQualifiedName = implPackage + interfaceClass.getName() + "Impl";

                        PsiClass implClass = JavaPsiFacade.getInstance(project)
                                .findClass(implQualifiedName, GlobalSearchScope.projectScope(project));

                        if (implClass != null) {
                            // 驗證是否確實實現了接口
                            boolean actuallyImplements = false;
                            for (PsiClassType implementedType : implClass.getImplementsListTypes()) {
                                PsiClass implemented = implementedType.resolve();
                                if (implemented != null && implemented.equals(interfaceClass)) {
                                    actuallyImplements = true;
                                    break;
                                }
                            }

                            if (actuallyImplements && !result.contains(implClass)) {
                                result.add(implClass);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("搜索可能的包路徑時出錯", e);
            }
        }

        return result;
    }

    /**
     * 判斷一個類是否是 Controller 類
     */
    private boolean isControllerClass(PsiClass psiClass) {
        String className = psiClass.getName();
        if (className == null) {
            return false;
        }

        // 檢查類名
        if (className.contains("Controller")) {
            return true;
        }

        // 檢查註解
        for (PsiAnnotation annotation : psiClass.getAnnotations()) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null &&
                    (qualifiedName.endsWith("Controller") || qualifiedName.endsWith("RestController"))) {
                return true;
            }
        }

        // 檢查包名
        String qualifiedName = psiClass.getQualifiedName();
        return qualifiedName != null && qualifiedName.contains(".controller.");
    }

    /**
     * 判斷一個類是否是 Service 類
     */
    private boolean isServiceClass(PsiClass psiClass) {
        String className = psiClass.getName();
        if (className == null) {
            return false;
        }

        // 檢查類名是否包含 "Service"
        if (className.contains("Service")) {
            return true;
        }

        // 檢查是否有 @Service 註解
        for (PsiAnnotation annotation : psiClass.getAnnotations()) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null && qualifiedName.endsWith("Service")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判斷一個類是否是 ServiceImpl 類
     */
    private boolean isServiceImplClass(PsiClass psiClass) {
        String className = psiClass.getName();
        return className != null && className.contains("Impl") && isServiceClass(psiClass);
    }

    /**
     * 查找與特定類相關的所有類
     */
    private List<PsiClass> findRelatedClasses(Project project, PsiClass sourceClass) {
        List<PsiClass> result = new ArrayList<>();
        String className = sourceClass.getName();
        if (className == null) {
            return result;
        }

        // 根據類的命名模式尋找相關類
        if (className.contains("Controller")) {
            // 如果是 Controller，找相關的 Service 和 ServiceImpl
            String baseName = className.replace("Controller", "");
            findRelatedServiceForController(project, sourceClass, baseName, result);
            findServicesByControllerReference(project, sourceClass, result);

        } else if (className.contains("Service") && !className.contains("Impl")) {
            // 如果是 Service 接口，找相關的 Controller 和實現類
            String baseName = className.replace("Service", "");
            findRelatedControllerForService(project, baseName, result);
            findControllersByServiceName(project, className, result);
            findServiceImplForService(project, sourceClass, result);

            if (result.isEmpty() || !hasControllerInResult(result)) {
                findRelatedClassesByReference(project, sourceClass, result);
            }

            if (!hasServiceImplInResult(result, className)) {
                findServiceImplByNamingConvention(project, sourceClass, result);
            }

        } else if (className.contains("ServiceImpl")) {
            // 如果是 ServiceImpl，找相關的 Controller 和接口
            String baseName = className.replace("ServiceImpl", "");
            findRelatedControllerForService(project, baseName, result);

            if (!hasControllerInResult(result)) {
                findControllersByServiceImplName(project, className, result);
            }

            findServiceInterfaceForImpl(project, sourceClass, result);

            // 尋找同級的 ServiceImpl 類
            for (PsiClassType interfaceType : sourceClass.getImplementsListTypes()) {
                PsiClass interfaceClass = interfaceType.resolve();
                if (interfaceClass != null && interfaceClass.getName() != null &&
                        interfaceClass.getName().contains("Service")) {
                    Collection<PsiClass> allImpls = findImplementingClasses(project, interfaceClass);
                    for (PsiClass impl : allImpls) {
                        if (!result.contains(impl) && !impl.equals(sourceClass)) {
                            result.add(impl);
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * 確保包含所有相關類的實現類
     */
    private List<PsiClass> getAllRelatedClassesWithImpls(Project project, List<PsiClass> initialClasses) {
        Set<PsiClass> allClasses = new HashSet<>(initialClasses);
        Queue<PsiClass> classesToCheck = new LinkedList<>(initialClasses);

        int iterationCount = 0;
        while (!classesToCheck.isEmpty() && iterationCount < 10) { // 設置最大迭代次數防止無限循環
            iterationCount++;

            PsiClass psiClass = classesToCheck.poll();
            String className = psiClass.getName();
            if (className == null)
                continue;

            // 如果是接口，查找所有實現類
            if (psiClass.isInterface() && className.contains("Service")) {
                // 查找服務接口的所有實現類
                Collection<PsiClass> impls = findImplementingClasses(project, psiClass);

                for (PsiClass impl : impls) {
                    if (!allClasses.contains(impl)) {
                        allClasses.add(impl);
                        classesToCheck.offer(impl);
                    }
                }
            }

            // 如果是實現類，確保我們也有其接口
            if (isServiceImplClass(psiClass)) {
                for (PsiClassType interfaceType : psiClass.getImplementsListTypes()) {
                    PsiClass interfaceClass = interfaceType.resolve();
                    if (interfaceClass != null && interfaceClass.getName() != null) {
                        // 添加接口
                        if (!allClasses.contains(interfaceClass)) {
                            allClasses.add(interfaceClass);
                            classesToCheck.offer(interfaceClass);
                        }

                        // 尋找相關的 Controller
                        if (interfaceClass.getName().contains("Service")) {
                            String baseName = interfaceClass.getName().replace("Service", "");
                            String controllerName = baseName + "Controller";

                            PsiClass[] controllers = JavaPsiFacade.getInstance(project)
                                    .findClasses(controllerName, GlobalSearchScope.projectScope(project));

                            for (PsiClass controller : controllers) {
                                if (!allClasses.contains(controller)) {
                                    allClasses.add(controller);
                                    classesToCheck.offer(controller);
                                }
                            }
                        }

                        // 尋找所有實現同一接口的其他實現類
                        Collection<PsiClass> siblingImpls = findImplementingClasses(project, interfaceClass);

                        for (PsiClass siblingImpl : siblingImpls) {
                            if (!allClasses.contains(siblingImpl)) {
                                allClasses.add(siblingImpl);
                                classesToCheck.offer(siblingImpl);
                            }
                        }
                    }
                }
            }

            // 如果是Controller，查找相關的Service和ServiceImpl
            if (className.contains("Controller")) {
                String baseName = className.replace("Controller", "");
                findRelatedServiceForController(project, psiClass, baseName, new ArrayList<>(allClasses));

                // 檢查方法體中使用的Service
                for (PsiMethod method : psiClass.getMethods()) {
                    List<PsiClass> serviceClasses = new ArrayList<>();
                    collectServiceClassesFromMethod(method, serviceClasses);

                    for (PsiClass serviceClass : serviceClasses) {
                        if (!allClasses.contains(serviceClass)) {
                            allClasses.add(serviceClass);
                            classesToCheck.offer(serviceClass);
                        }
                    }
                }
            }
        }

        return new ArrayList<>(allClasses);
    }

    // 下面是各種輔助方法

    /**
     * 檢查結果列表中是否有Controller類
     */
    private boolean hasControllerInResult(List<PsiClass> result) {
        for (PsiClass psiClass : result) {
            if (psiClass.getName() != null && psiClass.getName().contains("Controller")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 檢查結果列表中是否已經有對應的ServiceImpl
     */
    private boolean hasServiceImplInResult(List<PsiClass> result, String interfaceName) {
        String implName = interfaceName + "Impl";
        for (PsiClass psiClass : result) {
            if (psiClass.getName() != null && psiClass.getName().equals(implName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 從方法中收集使用的 Service 類
     */
    private void collectServiceClassesFromMethod(PsiMethod method, List<PsiClass> result) {
        // 檢查方法體中使用的 Service
        PsiCodeBlock body = method.getBody();
        if (body != null) {
            // 尋找所有變量引用
            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitReferenceExpression(PsiReferenceExpression expression) {
                    super.visitReferenceExpression(expression);

                    PsiElement resolved = expression.resolve();
                    if (resolved instanceof PsiField) {
                        PsiField field = (PsiField) resolved;
                        PsiClass fieldType = PsiUtil.resolveClassInType(field.getType());
                        if (fieldType != null && isServiceClass(fieldType) && !result.contains(fieldType)) {
                            result.add(fieldType);

                            // 如果是接口，也收集其實現類
                            if (fieldType.isInterface()) {
                                Collection<PsiClass> implementingClasses = findImplementingClasses(
                                        method.getProject(), fieldType);
                                for (PsiClass impl : implementingClasses) {
                                    if (!result.contains(impl)) {
                                        result.add(impl);
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    /**
     * 通過Controller查找可能使用的Service類
     */
    private void findServicesByControllerReference(Project project, PsiClass controllerClass, List<PsiClass> result) {
        // 檢查所有方法
        for (PsiMethod method : controllerClass.getMethods()) {
            PsiCodeBlock body = method.getBody();
            if (body == null)
                continue;

            // 檢查方法體中的所有引用表達式
            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    super.visitMethodCallExpression(expression);

                    // 獲取調用的方法
                    PsiMethod calledMethod = expression.resolveMethod();
                    if (calledMethod == null)
                        return;

                    // 獲取方法所屬的類
                    PsiClass calledClass = calledMethod.getContainingClass();
                    if (calledClass == null)
                        return;

                    String calledClassName = calledClass.getName();
                    if (calledClassName == null)
                        return;

                    // 檢查是否是Service或ServiceImpl
                    if ((calledClassName.contains("Service") &&
                            !result.contains(calledClass))) {
                        result.add(calledClass);

                        // 如果是接口，添加其實現類
                        if (calledClass.isInterface()) {
                            findServiceImplForService(project, calledClass, result);
                        }
                        // 如果是實現類，添加其接口
                        else if (calledClassName.contains("Impl")) {
                            findServiceInterfaceForImpl(project, calledClass, result);
                        }
                    }
                }

                @Override
                public void visitReferenceExpression(PsiReferenceExpression expression) {
                    super.visitReferenceExpression(expression);

                    // 檢查引用的字段
                    PsiElement resolved = expression.resolve();
                    if (resolved instanceof PsiField) {
                        PsiField field = (PsiField) resolved;
                        PsiClass fieldType = PsiUtil.resolveClassInType(field.getType());

                        if (fieldType != null && fieldType.getName() != null &&
                                fieldType.getName().contains("Service") &&
                                !result.contains(fieldType)) {
                            result.add(fieldType);

                            // 如果是接口，添加其實現類
                            if (fieldType.isInterface()) {
                                findServiceImplForService(project, fieldType, result);
                            }
                            // 如果是實現類，添加其接口
                            else if (fieldType.getName().contains("Impl")) {
                                findServiceInterfaceForImpl(project, fieldType, result);
                            }
                        }
                    }
                }
            });
        }
    }

    /**
     * 根據Service名稱查找可能的Controller類
     */
    private void findControllersByServiceName(Project project, String serviceName, List<PsiClass> result) {
        // 從Service名稱推測可能的Controller名稱
        String baseName = serviceName.replace("Service", "");
        String controllerName = baseName + "Controller";

        // 在整個項目中查找
        PsiClass[] controllers = JavaPsiFacade.getInstance(project)
                .findClasses(controllerName, GlobalSearchScope.projectScope(project));

        for (PsiClass controller : controllers) {
            if (!result.contains(controller)) {
                result.add(controller);
            }
        }
    }

    /**
     * 根據ServiceImpl名稱查找可能的Controller類
     */
    private void findControllersByServiceImplName(Project project, String serviceImplName, List<PsiClass> result) {
        // 從ServiceImpl名稱推測可能的Controller名稱
        String baseName = serviceImplName.replace("ServiceImpl", "");
        String controllerName = baseName + "Controller";

        // 在整個項目中查找
        PsiClass[] controllers = JavaPsiFacade.getInstance(project)
                .findClasses(controllerName, GlobalSearchScope.projectScope(project));

        for (PsiClass controller : controllers) {
            if (!result.contains(controller)) {
                result.add(controller);
            }
        }
    }

    /**
     * 通過查找引用找到關聯的類
     */
    private void findRelatedClassesByReference(Project project, PsiClass sourceClass, List<PsiClass> result) {
        // 查找所有引用這個類的地方
        Collection<PsiReference> references = ReferencesSearch.search(sourceClass,
                GlobalSearchScope.projectScope(project)).findAll();

        for (PsiReference reference : references) {
            PsiElement element = reference.getElement();
            PsiClass containingClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);

            if (containingClass != null) {
                String name = containingClass.getName();
                if (name != null) {
                    // 如果是Controller或ServiceImpl，添加到結果
                    if (name.contains("Controller") || name.contains("ServiceImpl")) {
                        if (!result.contains(containingClass)) {
                            result.add(containingClass);

                            // 如果找到Controller，也添加它可能使用的其他Service
                            if (name.contains("Controller")) {
                                findServicesByControllerReference(project, containingClass, result);
                            }
                            // 如果找到ServiceImpl，也添加它的接口
                            else if (name.contains("ServiceImpl")) {
                                findServiceInterfaceForImpl(project, containingClass, result);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 根據命名規則查找可能的ServiceImpl類
     */
    private void findServiceImplByNamingConvention(Project project, PsiClass serviceInterface, List<PsiClass> result) {
        String interfaceName = serviceInterface.getName();
        if (interfaceName == null)
            return;

        String implName = interfaceName + "Impl";

        // 嘗試常見的包路徑：
        // 1. 在同一個包中
        // 2. 在impl子包中
        // 3. 替換.service.為.service.impl.
        String qualifiedName = serviceInterface.getQualifiedName();
        if (qualifiedName == null)
            return;

        String packageName = qualifiedName.substring(0, qualifiedName.lastIndexOf('.') + 1);
        String[] possibleImplPackages = {
                packageName, // 同一個包
                packageName + "impl.", // impl子包
                packageName.replace(".service.", ".service.impl.") // 標準service.impl結構
        };

        for (String implPackage : possibleImplPackages) {
            String implQualifiedName = implPackage + implName;

            PsiClass implClass = JavaPsiFacade.getInstance(project)
                    .findClass(implQualifiedName, GlobalSearchScope.projectScope(project));

            if (implClass != null) {
                if (!result.contains(implClass)) {
                    result.add(implClass);
                }
            }
        }
    }

    /**
     * 查找與 Controller 方法相關的 Service 和 ServiceImpl 類
     */
    private void findRelatedServiceForController(Project project, PsiClass controller, String baseName,
            List<PsiClass> result) {
        String serviceName = baseName + "Service";
        String serviceImplName = baseName + "ServiceImpl";
        String controllerPackage = controller.getQualifiedName();

        if (controllerPackage != null) {
            // 查找可能的 Service 接口
            String servicePackage = controllerPackage.replace(controller.getName(), serviceName);
            PsiClass serviceInterface = JavaPsiFacade.getInstance(project)
                    .findClass(servicePackage, GlobalSearchScope.projectScope(project));

            if (serviceInterface != null) {
                if (!result.contains(serviceInterface)) {
                    result.add(serviceInterface);
                }

                // 查找 Service 接口的實現類
                findServiceImplForService(project, serviceInterface, result);
            }

            // 查找可能的 ServiceImpl 類
            String serviceImplPackage = controllerPackage.replace(controller.getName(), serviceImplName);
            PsiClass serviceImpl = JavaPsiFacade.getInstance(project)
                    .findClass(serviceImplPackage, GlobalSearchScope.projectScope(project));

            if (serviceImpl != null && !result.contains(serviceImpl)) {
                result.add(serviceImpl);
            }
        }
    }

    /**
     * 查找與 Controller 相關的 Service 和 ServiceImpl
     */
    private void findRelatedControllerForService(Project project, String baseName, List<PsiClass> result) {
        String controllerName = baseName + "Controller";

        // 查找可能的 Controller 類
        PsiClass[] controllers = JavaPsiFacade.getInstance(project)
                .findClasses(controllerName, GlobalSearchScope.projectScope(project));

        for (PsiClass controller : controllers) {
            if (!result.contains(controller)) {
                result.add(controller);
            }
        }
    }

    /**
     * 查找 Service 接口的實現類
     */
    private void findServiceImplForService(Project project, PsiClass serviceInterface, List<PsiClass> result) {
        if (!serviceInterface.isInterface()) {
            return;
        }

        // 查找所有實現此接口的類
        Collection<PsiClass> implementingClasses = findImplementingClasses(project, serviceInterface);
        for (PsiClass impl : implementingClasses) {
            if (!result.contains(impl)) {
                result.add(impl);
            }
        }
    }

    /**
     * 查找 ServiceImpl 類實現的接口
     */
    private void findServiceInterfaceForImpl(Project project, PsiClass serviceImpl, List<PsiClass> result) {
        for (PsiClassType interfaceType : serviceImpl.getImplementsListTypes()) {
            PsiClass interfaceClass = interfaceType.resolve();
            if (interfaceClass != null && !result.contains(interfaceClass)) {
                result.add(interfaceClass);
            }
        }
    }

    /**
     * 提取電文代號的主要部分（第一個連字符前的部分）
     */
    private String extractMainPartOfApiId(String apiId) {
        if (apiId == null) {
            return "";
        }

        // 提取第一部分，例如 "RET-B-TAKINGFILE" 提取 "RET"
        int firstHyphen = apiId.indexOf('-');
        if (firstHyphen > 0) {
            return apiId.substring(0, firstHyphen);
        }
        return apiId;
    }

    /**
     * 在 Swing EDT 線程中顯示錯誤訊息
     */
    private void showErrorMessage(String message, String title) {
        SwingUtilities.invokeLater(() -> {
            Messages.showErrorDialog(message, title);
        });
    }

    /**
     * 在 Swing EDT 線程中顯示提示訊息
     */
    private void showInfoMessage(String message, String title) {
        SwingUtilities.invokeLater(() -> {
            Messages.showInfoMessage(message, title);
        });
    }

    /**
     * [新增方法]
     * 查找 Controller 方法中直接或間接使用的 Service 接口和實現類【本身】。
     *
     * @param project          當前項目
     * @param controllerMethod 來源 Controller 方法
     * @param targetClasses    用於收集目標 Service/Impl 類的 Set (接口和實現都會被加入)
     */
    private void findRelatedServiceClassesOnly(Project project, PsiMethod controllerMethod,
            Set<PsiClass> targetClasses) {
        PsiCodeBlock body = controllerMethod.getBody();
        if (body == null)
            return;
        PsiClass controllerClass = controllerMethod.getContainingClass();
        if (controllerClass == null)
            return;

        LOG.debug("開始查找方法 " + controllerClass.getName() + "." + controllerMethod.getName() + " 使用的 Service 類...");

        body.accept(new JavaRecursiveElementWalkingVisitor() {

            // 輔助方法：嘗試添加 Service 類及其關聯類 (接口/實現) 到 Set
            private void addServiceAndRelatedIfApplicable(PsiClass potentialServiceClass) {
                if (potentialServiceClass == null || !isServiceClass(potentialServiceClass)) {
                    return; // 不是 Service 類，忽略
                }

                // 添加找到的 Service 類本身
                if (targetClasses.add(potentialServiceClass)) {
                    LOG.debug("  發現並添加 Service 類: " + potentialServiceClass.getName());
                    // 如果是接口，找到實現類並添加
                    if (potentialServiceClass.isInterface()) {
                        Collection<PsiClass> impls = findImplementingClasses(project, potentialServiceClass);
                        for (PsiClass impl : impls) {
                            if (isServiceClass(impl) && targetClasses.add(impl)) { // 確保實現類也是 Service
                                LOG.debug("    添加其實現類: " + impl.getName());
                            }
                        }
                    }
                    // 如果是實現類，找到接口並添加
                    else {
                        for (PsiClassType interfaceType : potentialServiceClass.getImplementsListTypes()) {
                            PsiClass interfaceClass = interfaceType.resolve();
                            if (interfaceClass != null && isServiceClass(interfaceClass)) { // 確保接口是 Service
                                if (targetClasses.add(interfaceClass)) {
                                    LOG.debug("    添加其接口: " + interfaceClass.getName());
                                }
                            }
                        }
                    }
                }
            }

            // 檢查方法調用
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiMethod calledMethod = expression.resolveMethod();
                if (calledMethod != null) {
                    addServiceAndRelatedIfApplicable(calledMethod.getContainingClass());
                }
            }

            // 檢查字段、局部變量等的引用
            @Override
            public void visitReferenceExpression(PsiReferenceExpression expression) {
                super.visitReferenceExpression(expression);
                PsiElement resolved = expression.resolve();
                PsiClass targetClass = null;
                if (resolved instanceof PsiField) {
                    targetClass = PsiUtil.resolveClassInType(((PsiField) resolved).getType());
                } else if (resolved instanceof PsiLocalVariable) {
                    targetClass = PsiUtil.resolveClassInType(((PsiLocalVariable) resolved).getType());
                } else if (resolved instanceof PsiParameter) {
                    targetClass = PsiUtil.resolveClassInType(((PsiParameter) resolved).getType());
                }
                addServiceAndRelatedIfApplicable(targetClass);
            }

            // 檢查 new ServiceImpl()
            @Override
            public void visitNewExpression(PsiNewExpression expression) {
                super.visitNewExpression(expression);
                PsiJavaCodeReferenceElement classReference = expression.getClassReference();
                if (classReference != null) {
                    PsiElement resolved = classReference.resolve();
                    if (resolved instanceof PsiClass) {
                        addServiceAndRelatedIfApplicable((PsiClass) resolved);
                    }
                }
                // 匿名內部類等更複雜情況暫不處理
            }
        });
        LOG.debug("查找 Service 類完成，共找到 " + targetClasses.size() + " 個。");
    }
}