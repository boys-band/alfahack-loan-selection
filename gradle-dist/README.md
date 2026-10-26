# Дистрибутив Gradle для Docker-сборок

Docker-образы всех шести Java-сервисов собираются только с локальным дистрибутивом
Gradle из этой папки; из интернета он не скачивается. Перед первой сборкой положите
сюда `gradle-8.14.3-bin.zip`:

```bash
curl -fL -o gradle-dist/gradle-8.14.3-bin.zip https://services.gradle.org/distributions/gradle-8.14.3-bin.zip
```

Контрольная сумма архива по-прежнему проверяется по `distributionSha256Sum` из
`gradle/wrapper/gradle-wrapper.properties` каждого сервиса:
`bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`.

Архив не хранится в git (больше лимита GitHub) и сохраняется на сервере при деплое.
Без него сборка завершается ошибкой `"/gradle-8.14.3-bin.zip": not found`.
Локальный `./gradlew` вне Docker по-прежнему скачивает Gradle сам.
